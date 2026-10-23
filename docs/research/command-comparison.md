# Command comparison: One Agent Studio and Automation 360

One row per command (action) of ours, compared with the matching AA action. Built by `tools/command-table.js` from the
action manifests, `command-comparison.data.json` (the findings) and the sample-task results of `CommandSamplesTest`
(`platform/packages/src/test/resources/command-samples/`). AA behaviour is summarised in our own words.

How each command was checked: its settings form in Studio (fields, defaults, required marks, drop-downs, which options
appear for which choice, help text), a small sample agent run through the real runner with the variables checked after
the run, and AA's documented settings for the same action (`property-gaps/*.md`; AA's editor needs a bot, see the
note at the end). Differences found were fixed or listed as proposals. From group 5 on, AA's own settings panels were
also read in AA's editor (an empty scratch bot, nothing saved).

| | Count |
| --- | --- |
| Commands (actions) in Studio | 997 |
| Checked so far | 997 |
| With a passing sample task | 445 |
| Connector commands run against a mock server (ConnectorSmokeTest) | 561 (0 failing) |
| With a fix or new option | 125 |
| Conditions and loop iterators (also listed) | 55 |

## Changes for every command

- **Options that apply only to some choices are hidden until they apply** (AA disables or hides them too). Toolkits
  declare it with `@Input(visibleWhen = "field=value|value")`; the manifest carries `x-ooa-visible-when` and Studio
  redraws the form when the controlling field changes. A value already set stays visible so it can be cleared.
- **Number limits** (`@Input(min, max)`, JSON Schema `minimum`/`maximum`): shown in the field's help, marked in red when
  out of range, and reported by the validator before a run.

## 1. Logic, flow and variables

### Core (`core`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `break` Exit loop | Loop/Break; Trigger loop/Break | Same, plus a label to leave an outer loop (AA has no labels). |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `comment` Comment | Comment/Comment | Same. |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `continue` Continue loop | Loop/Continue | Same, plus a label for nested loops. |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `delay` Delay | Delay/Delay | AA: fixed or random delay in seconds or milliseconds. Ours had the same. | New unit 'minutes'; limits (no negative delays) shown in Studio and checked by the validator. | Proposal: 'wait until a time of day' (e.g. until 18:00 local) as a delay mode. | pass (3) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `if` If | If/If; If/Else If; If/Else | AA builds each condition from a typed form (one family per variable type, up to 32 conditions, And/Or). Ours: one condition editor with all/any/not groups, expressions and toolkit conditions; same operators plus between, not-in and the ignore-case/trim/line-break/date-only options (added 21 Oct). |  | Proposal: show the condition as a sentence in the canvas and offer 'test this condition' with the current variable values. | pass (2) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `log` Log message |  | No AA equivalent in the editor (AA logs to file through the Logging package); ours writes to the run log with a level. |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `loop` Loop | Loop/Loop | AA shows only the fields of the chosen loop type; ours showed all 15 fields at once. The built-in 'each key' and 'each value' iterators existed at run time but were missing from Studio's Loop type list. | Fields now appear only for the chosen loop type (shown-when rules); Loop type list gained 'Each key' and 'Each value'; limits on count, max_iterations, from/to index. | Done: safety limit (max_iterations) that AA lacks. Proposal: loop over pages of an API (cursor) as a built-in iterator. | pass (6) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `parallel` Parallel |  | No AA equivalent (AA runs steps one at a time). Ours: branches run at the same time. | max_concurrency limited to 1-64. |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `pause` Pause agent | Task Bot/Pause | AA Task Bot > Pause waits for a person. Ours adds a message and an optional automatic resume time-out. | Time-out limit >= 0. |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `run_bot` Run Agent | Task Bot/Run | AA Task Bot > Run: inputs, outputs to a dictionary, repeat, continue on error. Ours: same plus output_map to variables, timeout, shared sessions, error output. | 'Delay between repeats' appears only when repeat is more than 1; limits on repeat and time-outs. |  | pass (2) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `set` Assign variable | Credential/Assign; String/Assign; Number/Assign; Boolean/Assign; List/Assign; Dictionary/Assign; Record/Assign; Data Table/Assign; File/Assign | Covers AA's Assign actions of every type (String, Number, Boolean, List, Dictionary, Record, Data Table, Credential) in one step with expressions and ${} interpolation. |  |  | pass (2) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `step` Step | Step/Step | Same (title). |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `stop` Stop agent | Task Bot/Stop | AA Task Bot > Stop. Ours: success or failed status; Finally blocks still run. |  |  | pass (2) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `throw` Throw error | Error handler/Throw | AA: message only. Ours: code, message, suggested fix, details, retryable. |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `try` Try | Error handler/Try; Error handler/Catch; Error handler/Finally | AA: Catch by exception type, message and line to variables, 'continue on error in Catch'. Ours: catch by error-code patterns (more precise), message/line/details to variables, screenshot, rethrow, continueOnError (added 21 Oct). Typed error dictionary with code, message, suggestedFix, line, action, stepId. |  |  | pass (1) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |
| `wait_until` Wait for condition | Wait/Wait for condition | AA: one condition with its own wait time, stop or carry on. Ours: any condition, timeout, poll interval, fail_on_timeout, output met. | Limits: timeout >= 0, poll >= 0.05 s. |  | pass (2) | CommandSamplesTest (cli, 01-core.json), CoreOptionsTest, Studio UI test (Loop fields) |

### Run context (`ooa.run_context`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get` Get run information |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07) |
| `get_value` Get one run value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07) |

### Shared values (`ooa.global_values`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get` Get shared value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07) |
| `get_all` Get all shared values |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07) |
| `is_defined (condition)` Shared value is defined |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07) |

## 2. Text and data

### String (`ooa.string`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `change_case` Change case | String/Lowercase; String/Uppercase | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `compare` Compare | String/Compare | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `extract_text` Extract text | String/Extract text | Same options (after/before markers with occurrence, ignore case, if not found, max length, remove line breaks). | Occurrence fields show only when their marker is filled. |  | pass (2) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `find` Find | String/Find | AA Find returns a 1-based position. Ours returns 0-based index, 1-based position, match, occurrence. | New outputs: regex capture groups, named groups, every match and the match count; limits on start and occurrence. | Done (typed outputs). Proposal: an AI-assisted 'build a pattern from examples' helper in Studio. | pass (2) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `length` Get length | String/Length | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `random_string` Random text | String/Generate random string | Same options as documented in property-gaps; nothing further found. | Letter case shows only when letters are on; length limited to 1-100000. |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `replace` Replace | String/Replace | Same options as documented in property-gaps; nothing further found. | Limits on start and count. |  | pass (2) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `reverse` Reverse | String/Reverse | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `split` Split | String/Split | Same options as documented in property-gaps; nothing further found. | 'Ignore case' hidden for regex splits (use (?i) there); limit on max parts. |  | pass (2) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `substring` Get part of text | String/Substring | AA uses 1-based start; ours 0-based (documented). | Limits on start, length and end. |  | pass (3) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `to_locale_number` Number to locale text |  | Defect found by the sample task: en-IN (the field's own example) grouped digits in threes (1,234,567). | Lakh/crore grouping for India, Pakistan, Bangladesh and Nepal (12,34,567). |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `to_number` Text to number | String/To locale number; String/To number | AA has To number and To locale number; ours is one action with locale and separator overrides. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `trim` Trim | String/Trim | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `is_email (condition)` Text is an email address |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |
| `is_number (condition)` Text is a number |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 02-string.json), DataGapsTest |

### Number (`ooa.number`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `decrement` Decrement | Number/Decrement | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |
| `format` Format number |  | Same defect as Number to locale text for en-IN. | Lakh/crore grouping; decimals limited to 0-20. |  | pass (2) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |
| `increment` Increment | Number/Increment | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |
| `random` Random number | Number/Random | Same options as documented in property-gaps; nothing further found. | Decimals limited to 0-15. |  | pass (1) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |
| `round` Round |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |
| `to_text` Number to text | Number/To string | Same options as documented in property-gaps; nothing further found. | Rounding mode shows only when no custom pattern is used; decimals 0-20. |  | pass (1) | CommandSamplesTest (cli, 03, 04), DataOptionsTest |

### Boolean (`ooa.boolean`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `compare` Compare | Boolean/Compare to; Boolean/Equal to | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03), DataGapsTest |
| `from_text` Text to Boolean | String/To boolean | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 03), DataGapsTest |
| `invert` Invert | Boolean/Invert | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03), DataGapsTest |
| `to_number` Boolean to number | Boolean/To number | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03), DataGapsTest |
| `to_text` Boolean to text | Boolean/To string | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 03), DataGapsTest |

### List (`ooa.list`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_item` Add item | List/Add item | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `append_list` Append list | List/Append | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `clear` Clear | List/Clear | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `get_item` Get item | List/Get item | Same options as documented in property-gaps; nothing further found. | Default value shows only when 'if out of range' is 'default'. |  | pass (2) | CommandSamplesTest (cli, 04), DataGapsTest |
| `index_of` Find item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `join` Join | List/Join items | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `remove_duplicates` Remove duplicates |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `remove_item` Remove item | List/Remove item | Same options as documented in property-gaps; nothing further found. | 'All occurrences' shows only when removing by value. |  | pass (2) | CommandSamplesTest (cli, 04), DataGapsTest |
| `reverse` Reverse |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `set_item` Set item | List/Set item | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 04), DataGapsTest |
| `size` Get size | List/Size | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `sort` Sort |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `contains (condition)` List contains item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `is_empty (condition)` List is empty |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |

### Dictionary (`ooa.dictionary`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get` Get | Dictionary/Get | Same options as documented in property-gaps; nothing further found. | Default shows only when 'fail if missing' is off. |  | pass (2) | CommandSamplesTest (cli, 04), DataGapsTest |
| `keys` Get keys |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `merge` Merge |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `put` Put | Dictionary/Put; Record/Add column; Record/Update column | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 04), DataGapsTest |
| `remove` Remove | Dictionary/Remove; Record/Delete column | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `size` Get size | Dictionary/Size | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `values` Get values |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `contains_key (condition)` Contains key |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `contains_value (condition)` Contains value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `is_empty (condition)` Dictionary is empty |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |
| `key_has_value (condition)` Key has value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 04), DataGapsTest |

### Data Table (`ooa.datatable`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_row` Add row |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `aggregate` Group and summarise |  | New: AA has no grouping or summaries (sums per vendor need a loop and a dictionary). | New action Group and summarise: group by columns; count, sum, avg, min, max, first, last, count_distinct. | Done. | pass (2) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `change_column_type` Change column type | Data Table/Change column type | Same options as documented in property-gaps; nothing further found. | Date format shows only for DateTime; impossible dates (31/02) are now refused instead of being changed to 28/02. |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `clear` Clear rows | Data Table/Clear content | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `column_count` Column count | Data Table/Get number of columns | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `column_name` Column name | Data Table/Get column name | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `column_names` Column names |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `delete_column` Delete column | Data Table/Delete column | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `delete_row` Delete rows | Data Table/Delete row | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `filter` Filter rows |  | Same options as documented in property-gaps; nothing further found. | Value hidden for empty/not empty; match case only for text operators. |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `find` Find value | Data Table/Search for a value | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `from_records` Create from records |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `get_value` Get value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `insert_column` Insert column | Data Table/Insert column | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `insert_row` Insert row | Data Table/Insert row | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `join` Join tables | Data Table/Join | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `merge` Merge tables | Data Table/Merge | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `remove_duplicates` Remove duplicate rows | Data Table/Remove duplicate rows | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `rename_column` Rename column |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `row_count` Row count | Data Table/Get number of rows | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `set_value` Set value | Data Table/Set value of a single cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `sort` Sort | Data Table/Sort | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `sum_column` Sum column |  | Same options as documented in property-gaps; nothing further found. |  | Superseded by Group and summarise for anything beyond one total. | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `to_records` Convert to records |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `write_to_file` Write to file | Data Table/Write to file | Same options as documented in property-gaps; nothing further found. | Delimiter, header, append and line ending show only for CSV. |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `column_count_is (condition)` Column count is |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `contains_value (condition)` Column contains value |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `has_column (condition)` Table has column |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `is_empty (condition)` Table is empty |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |
| `row_count_is (condition)` Row count is |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 05-datatable.json), DataGapsTest |

### Date and time (`ooa.datetime`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add` Add to date | Datetime/Add | AA: units ms to years. | New unit business_days (skips weekends and an optional holiday list, shown only for that unit). | Done. Proposal: named holiday calendars kept in Command Center. | pass (2) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `convert_time_zone` Convert time zone |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `difference` Difference between dates | Datetime/Difference between dates | Same options as documented in property-gaps; nothing further found. | New unit business_days (working days between two dates) with holidays. |  | pass (2) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `end_of` End of period |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `format` Format date | Datetime/To string | Same options as documented in property-gaps; nothing further found. | Pattern hidden when a standard format is chosen. |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `get_part` Get part of date | Datetime/Get | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_after` Is after | Datetime/Is after | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_before` Is before | Datetime/Is before | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_equal` Is equal | Datetime/Is equal | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_leap_year` Is leap year | Datetime/Is leap year | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `now` Get current date and time |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `parse` Text to date | Datetime/Assign | Defect found by the sample task: 31/02/2026 was accepted and silently became 28 Feb. | Strict reading: impossible dates fail with DATETIME.PARSE_FAILED; pattern hidden when a standard format is chosen. |  | pass (3) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `start_of` Start of period |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `subtract` Subtract from date | Datetime/Subtract | Same options as documented in property-gaps; nothing further found. | New unit business_days with holidays. |  | pass (2) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_after (condition)` Date is after | Datetime/Is after | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_before (condition)` Date is before | Datetime/Is before | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_between (condition)` Date is between |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_business_hours (condition)` Within business hours |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_equal (condition)` Dates are equal | Datetime/Is equal | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_valid (condition)` Text is a valid date |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |
| `is_weekend (condition)` Is weekend |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 06-datetime.json), DataOptionsTest, Studio UI test (holidays field) |

### JSON (`ooa.json`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `delete_node` Delete node |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `get_node` Get node value | JSON utilities/Get node value | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 07), DataGapsTest |
| `get_node_array` Get node list | JSON utilities/Get node array | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `get_nodes` Get several node values | JSON utilities/Get nodes | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `insert_node` Insert node | JSON utilities/Insert node | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 07), DataGapsTest |
| `parse` Parse JSON | JSON utilities/Convert JSON to Dictionary | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 07), DataGapsTest |
| `read_file` Read JSON file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `stringify` Convert to JSON text | JSON utilities/Convert dictionary to JSON | Same options as documented in property-gaps; nothing further found. | Indent shows only with pretty printing; limited to 0-10. |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `to_table` Convert to table | JSON utilities/Convert JSON to Table | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `update_node` Update node | JSON utilities/Update node value | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `validate` Validate JSON | JSON utilities/Validate | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `write_file` Write JSON file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |

### XML (`ooa.xml`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `delete_nodes` Delete nodes | XML/Delete node | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `get_nodes` Get multiple nodes | XML/Get multiple nodes | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `get_value` Get single node | XML/Get single node | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 07), DataGapsTest |
| `insert_node` Insert node | XML/Insert node | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `save_file` Save XML file | XML/Save session data | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `set_value` Update node | XML/Update node | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `to_dictionary` Convert to dictionary |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `validate` Validate XML | XML/Validate XML document | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `xpath_function` Evaluate XPath | XML/Execute XPath function | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |
| `nodes (iterator)` Each node in XML |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataGapsTest |

### CSV/TXT (`ooa.csv`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `read` Read to table | CSV/TXT/Open; CSV/TXT/Read | AA returns every value as text (as ours did), so arithmetic on a CSV column needs conversions; the sample task showed 0 + '1' concatenating. | New option detect_types: numbers, true/false and ISO dates become typed values; codes with leading zeros stay text. Limits on skip_lines and max_rows. | Done. | pass (2) | CommandSamplesTest (cli, 07), DataOptionsTest |
| `write` Write from table |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataOptionsTest |
| `rows (iterator)` Each row in CSV/TXT |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 07), DataOptionsTest |

## 3. Files and folders

### File (`ooa.file`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `copy` Copy | File/Copy Desktop file | Same options as documented in property-gaps; nothing further found. | 'Keep both' shows only when overwrite is off. |  | pass (2) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `create_shortcut` Create shortcut | File/Create shortcut | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `create_symbolic_link` Create symbolic link | File/Create symbolic link | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `delete` Delete | File/Delete | Same options as documented in property-gaps; nothing further found. | Limits on the size and age filters. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `get_info` Get file info |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `get_name` Get file name | File/Get name | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `get_parent` Get parent folder | File/Get path | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `list` List files |  | AA has no list action (its loop 'each file in folder' lists); ours lists with size and age filters. | New sort_by (name, modified, size) and descending; new outputs count and first (e.g. the newest file); limits on the size and age filters. | Done: 'latest file in a folder' in one step. | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `move` Move or rename | File/Rename | Same options as documented in property-gaps; nothing further found. | 'Keep both' shows only when overwrite is off. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `print_files` Print files | File/Print; File/Print multiple files | An empty selection fails with FILE.NO_FILES (AA silently prints nothing). | The files list and the folder/pattern options exclude each other in the form; copies 1-999. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `read_text` Read text | Text file/Get text | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `read_variables` Read variables from file | Text file/Read variables | New: AA Text file > Read variables had no equivalent. | New action: name=value lines into a Dictionary (separator, encoding, trim; # and ; comments skipped). |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `unzip` Unzip | Folder/Unzip | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `wait_for_file` Wait for file |  | New: AA has no step that waits for a download or export to finish (people add fixed delays). | New action: waits until a file (name may use * and ?) appears and stops growing; skips .crdownload/.part/.tmp and ~$ lock files; time-out with fail or carry on. | Done. | pass (3) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `write_text` Write text | File/Create | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `zip` Zip | Folder/Zip | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `date_is (condition)` File date is |  | New: AA's If has 'file date' conditions. | New condition: modified or created date before/after/on a date, or older/newer than N days. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `exists (condition)` File exists |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `has_extension (condition)` File has extension |  | New: AA's If has a 'file extension' condition. | New condition: one or more extensions, any case, with or without the dot. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `size_is (condition)` File size is |  | New: AA's If has 'file size' conditions. | New condition: compare size in bytes, KB, MB or GB. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `files_in_folder (iterator)` Each file in folder |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |

### Folder (`ooa.folder`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `copy` Copy folder | Folder/Copy | Same options as documented in property-gaps; nothing further found. | 'Only newer' shows only when overwriting. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `create` Create folder | Folder/Create | Same options as documented in property-gaps; nothing further found. |  | Proposal: one error-code family for the File and Folder toolkits (today FILE.NOT_FOUND and FOLDER.NOT_FOUND both occur), with the old codes kept as aliases. | pass (2) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `create_shortcut` Create folder shortcut | Folder/Create shortcut | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `create_symbolic_link` Create folder symbolic link | Folder/Create symbolic link | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `delete` Delete folder | Folder/Delete | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `list_subfolders` List subfolders |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `open_folder` Open folder | Folder/Open | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `rename_folder` Rename folder | Folder/Rename | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `exists (condition)` Folder exists |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `is_empty (condition)` Folder is empty |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |
| `subfolders (iterator)` Each folder in folder |  | New: AA Loop 'each folder in folder' had no iterator of ours. | New loop iterator with sub-folders at any depth and a name pattern. |  | pass (1) | CommandSamplesTest (cli, 08-files.json), PropertyGapsTest |

## 4. Excel

### Excel (no Office needed) (`ooa.excel`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_sheet` Create worksheet | Excel advanced/Create worksheet | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `append_workbook` Append workbook | Excel advanced/Append workbook | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the append_worksheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `append_worksheet` Append worksheet | Excel advanced/Append worksheet | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `break_links` Break workbook links | Excel advanced/Break workbook links | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `cell_address` Get cell address | Excel advanced/Get cell address; Excel basic/Get cell address | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `change_link` Change workbook link | Excel advanced/Update workbook links | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `close` Close | Excel advanced/Close; Excel basic/Close | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `column_name` Get column name | Excel advanced/Get column name; Excel basic/Get column name | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `copy_sheet` Copy worksheet |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `create_workbook` Create workbook | Excel advanced/Create workbook | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `delete_cells` Delete cells | Excel advanced/Delete cells; Excel basic/Delete cells | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `delete_columns` Delete columns |  | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (in the insert_columns sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `delete_rows` Delete rows |  | Same options as documented in property-gaps; nothing further found. | Row and count limited to 1 or more, so row 0 is reported before the run instead of failing in it. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `delete_sheet` Delete worksheet | Excel advanced/Delete worksheet | Same options as documented in property-gaps; nothing further found. | Name and index exclude each other. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `delete_table_column` Delete table column | Excel advanced/Delete table column | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `filter` Filter | Excel advanced/Filter | Same options as documented in property-gaps; nothing further found. | 'Value to' shows only for between; value hidden for empty/not empty; match case only for text operators. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `find` Find | Excel advanced/Find; Excel basic/Find | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `find_next_empty_cell` Find next empty cell | Excel advanced/Find next empty cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_all_cells` Get all cells | Excel advanced/Get worksheet as data table | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_workbook sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_cell` Get cell | Excel advanced/Get single cell; Excel basic/Get single cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_workbook sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_cell_color` Get cell colour | Excel advanced/Get cell color | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the set_cell_color sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_current_sheet_name` Get current sheet name | Excel advanced/Get current worksheet name | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_links` Get workbook links | Excel advanced/Get workbook links | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_range` Get range | Excel basic/Get multiple cells | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_sensitivity_label` Get sensitivity label | Excel advanced/Get sensitivity label | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the set_sensitivity_label sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_sheet_names` Get sheet names | Excel advanced/Get worksheet names | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `get_table_range` Get table range | Excel advanced/Get table range | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `go_to_cell` Go to cell | Excel advanced/Go to cell; Excel basic/Go to cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `go_to_next_empty_cell` Go to next empty cell | Excel advanced/Go to next empty cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `hide_columns` Hide columns |  | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `hide_rows` Hide rows | Excel advanced/Hide rows/columns in selection | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `hide_sheet` Hide sheet | Excel advanced/Hide worksheet | Same options as documented in property-gaps; nothing further found. | Name and index exclude each other. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `insert_columns` Insert columns |  | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `insert_rows` Insert rows | Excel advanced/Insert/Delete rows/columns | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `insert_table_column` Insert table column | Excel advanced/Insert table column | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `open` Open workbook | Excel advanced/Open; Excel basic/Open | Missing file gives EXCEL.NOT_FOUND (the File toolkit uses FILE.NOT_FOUND). |  | Proposal: let a Catch match any not-found error (*.NOT_FOUND) so toolkits need not share codes. | pass (2) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `protect_sheet` Protect sheet | Excel advanced/Password protect worksheet | The password was a plain String field, unlike Open and Save which take vault credentials. | Password is a Credential input (vault or text); an empty password is refused instead of silently leaving the sheet unprotected. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `protect_workbook` Protect workbook | Excel advanced/Protect workbook | Same options as documented in property-gaps; nothing further found. | Password is a Credential input. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `read_column` Read column | Excel advanced/Read column | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `read_formula` Read cell formula | Excel advanced/Read cell formula | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_workbook sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `read_row` Read row | Excel advanced/Read row | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `remove_blank_rows` Remove blank rows | Excel advanced/Remove blank rows | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `rename_sheet` Rename worksheet | Excel advanced/Rename worksheet | Same options as documented in property-gaps; nothing further found. | Name and index exclude each other. |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `replace` Replace | Excel advanced/Replace; Excel basic/Replace | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `row_count` Get number of rows | Excel advanced/Get number of rows | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the insert_rows sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `row_number` Get row and column number | Excel advanced/Get row number; Excel basic/Get row number | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `save` Save | Excel advanced/Save workbook; Excel basic/Save workbook | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `save_as` Save as | Excel advanced/Save as | Same options as documented in property-gaps; nothing further found. | Sheet shows only for CSV. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `select_range` Select range | Excel advanced/Select cells/rows/columns | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `set_cell` Set cell | Excel advanced/Set cell; Excel basic/Set cell | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the append_worksheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `set_cell_color` Set cell colour |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `set_formula` Set formula | Excel advanced/Set cell formula | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_workbook sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `set_sensitivity_label` Set sensitivity label | Excel advanced/Set sensitivity label | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `sheet_count` Get sheet count | Excel advanced/Retrieve sheets count | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `sort_range` Sort | Excel advanced/Sort | Same options as documented in property-gaps; nothing further found. | 'Then descending' shows only with a second sort column. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `switch_sheet` Switch to sheet | Excel advanced/Switch to sheet; Excel basic/Switch to sheet | Same options as documented in property-gaps; nothing further found. | Sheet name and 1-based index exclude each other in the form. |  | pass (in the add_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unhide_all_sheets` Unhide all sheets | Excel advanced/Unhide all worksheets | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the hide_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unhide_columns` Unhide columns |  | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (in the hide_columns sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unhide_rows` Unhide rows | Excel advanced/Unhide rows/columns in selection | Same options as documented in property-gaps; nothing further found. | Limits on row, column, count or position (1-based). |  | pass (in the hide_rows sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unhide_sheet` Unhide sheet | Excel advanced/Unhide worksheet | Same options as documented in property-gaps; nothing further found. | Name and index exclude each other. |  | pass (in the hide_sheet sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unprotect_sheet` Unprotect sheet | Excel advanced/Access password protected worksheet | Same options as documented in property-gaps; nothing further found. | Password is a Credential input. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `unprotect_workbook` Unprotect workbook | Excel advanced/Unprotect workbook | Same options as documented in property-gaps; nothing further found. | Password is a Credential input. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `write_table` Write from Data Table | Excel advanced/Write from data table | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the delete_cells sample) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |
| `rows (iterator)` Each row in a worksheet | Excel advanced/Get multiple cells | New: AA's Loop has 'each row in an Excel worksheet'; ours needed Get all cells first. | New loop iterator: each row (of the sheet or a range) as a record by header names; hidden rows can be skipped. |  | pass (1) | CommandSamplesTest (cli, 09-excel.json), ExcelGapsTest, ExcelPropertiesTest |

## 5. Web and browser

### Browser (`ooa.browser`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `attach` Attach to open browser |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `call_javascript_function` Call JavaScript function | Browser/Call a JavaScript function | Same (function, arguments, return type; AA defaults the return type to none, ours to any). |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `click` Click element |  | With a plain selector a missing element fails as BROWSER.TIMEOUT, with a captured object as BROWSER.OBJECT_NOT_FOUND, so one Catch may not see both. |  | Proposal: error-code aliases (one family for 'not found') so Catch blocks can match both without breaking existing ones. | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `close` Close browser | Browser/Close | AA: tab, window or all browsers with a tab picker and a time-out. Ours: also 'this session'; no time-out (the step time-out applies). | Tab picker shows only for tab/window. |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `download` Download file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `download_url` Download file from address | Browser/Download file | AA Download file: URL, save to, overwrite (sign-in implied). Ours adds explicit user/password and a time-out. |  |  | pass (2) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `element_action` Capture: element action | Recorder/Capture | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `extract_table` Extract table | Recorder/Structured data extraction | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `find_broken_links` Find broken links | Browser/Find broken links | Same options; AA requires the CSV file and defaults to ANSI, ours makes the file optional (table output) and defaults to UTF-8. | Pages limit only for scope site; append and encoding only with a CSV file; limits on threads and time-outs. |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `get_attribute` Get attribute |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `get_page_source` Get page source | Browser/Get source code | Same (tab, optional iframe, time-out, output); ours can also save to a file. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `get_text` Get text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `history` Back, forward or refresh | Browser/Go back | AA Go back: steps and 'error if too many'; ours also forward and refresh. | Steps and the error option show only for back/forward. |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `list_windows` List windows (design time) |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `navigate` Go to URL |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `open` Open browser | Browser/Open | Checked in AA's editor: what to open (existing tab, new tab, new window), browser incl. 'default browser', link, time-out in seconds (240, min 9). Ours: same modes; browser edge/chrome/chromium/firefox (no 'default'); time-out in milliseconds. | Browser, headless and download folder show only for a new window; the tab picker only for new/existing tab; time-out at least 1 s. | Proposal: a 'default browser' choice read from the user's Windows setting. | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `open_for_automation` Start browser for automation |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `page_info` Get URL and title |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the navigate sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `pick_element` Pick element (design time) |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `record` Record web actions |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `run_javascript` Run JavaScript | Browser/Run JavaScript | Same (tab, iframe, script typed or from a file, time-out). | Script and script file exclude each other in the form, as in AA. |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `screenshot` Take screenshot |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `select_option` Select item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `switch_tab` Switch tab |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `test_object` Test captured object (design time) |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `type_text` Set text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `wait_for` Wait for element |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `element_exists (condition)` Element exists |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `element_visible (condition)` Element is visible |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the element_exists sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |
| `text_on_page (condition)` Page contains text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the element_exists sample) | CommandSamplesTest (cli, 10-browser.json: headless Edge against a local test page), BrowserGapsTest |

### Web goal agent (`ooa.web_agent`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `end_session` End web agent session | UI Agent - Web/End Session | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run` Reach a goal | UI Agent - Web/Run | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `start_session` Start web agent session | UI Agent - Web/Start Session | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> BROWSER.ERROR | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

## 6. Window and desktop

### Window (`ooa.window`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `activate` Activate window | Window/Activate | Defect found by the sample task on the real desktop: Activate reported success while Windows' focus lock kept another app in front. | Activate now checks the window really is in front; if Windows refuses, it attaches to the foreground input and sends an ALT tap (the usual workaround), and otherwise fails with WINDOW.NOT_ACTIVATED. Capture window and the window events use the same routine. |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `capture` Capture window | Screen/Capture window | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `close` Close window | Window/Close | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `close_all` Close all matching windows | Window/Close all | Checked in AA's editor: AA closes all windows except a 'keep open' list. Ours closes windows matching a title (safer). | New keep list; the agent's own windows (run window, Studio) are never closed. |  | pass (1), 1 skipped here | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `get_active` Get active window title | Window/Get active window title | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `get_info` Get window details |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `list` List windows |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `maximize` Maximise window | Window/Maximize | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `minimize` Minimise window | Window/Minimize | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `move_resize` Move and resize window | Window/Resize | AA Resize needs all four values; ours leaves empty ones unchanged. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `restore` Restore window | Window/Restore | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `set_title` Set window title | Window/Set title | Same (AA can match the window by regular expression; ours matches title text or alternatives). |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `wait_for` Wait for window | Wait/Wait for window | Checked in AA's editor: AA waits for a window to open or to close, 5 s default, and raising an error is optional. Ours only waited for it to open and always failed. | New state (open or close), fail_on_timeout and output met. |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `exists (condition)` Window exists |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |
| `is_active (condition)` Window is active |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11-window-desktop.json, run with OOA_DESKTOP_SAMPLES=1), WindowGapsTest |

### Mouse, keyboard and screen (`ooa.desktop`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `capture_screen` Capture screen | Screen/Capture area; Screen/Capture desktop | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `clipboard_clear` Clear clipboard | Clipboard/Clear | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `clipboard_get` Get clipboard text | Clipboard/Copy from | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `clipboard_set` Copy to clipboard | Clipboard/Copy to | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `message_box` Message box | Message box/Message box | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `mouse_click` Mouse click | Mouse/Click | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `mouse_move` Mouse move | Mouse/Move | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `mouse_scroll` Mouse scroll | Mouse/Scroll | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `press_keys` Press keys |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `prompt_file` Prompt for file | Prompt/For file | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `prompt_folder` Prompt for folder | Prompt/For folder | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `prompt_password` Prompt for password |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `prompt_value` Prompt for value | Prompt/For value | Defect found by the sample task: on time-out the value was empty instead of the default. | Time-out returns the default; new output timed_out. |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `prompt_yes_no` Prompt for yes/no | Prompt/For yes/no | Same options as documented in property-gaps; nothing further found. | 'Answer on time-out' shows only when a time-out is set. |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |
| `type_text` Type text | Simulate keystrokes/Simulate keystrokes | Text in brackets that is not a key name is typed as text (by design), so a typo such as [ENTRE] is typed silently. | Keystroke delay shows only for keystrokes; restore clipboard only for paste. | Proposal: warn in the run log when a bracketed word looks like a key name but is unknown. | unit test | CommandSamplesTest (cli, 11, desktop), SystemGapsTest |

### Desktop objects (`ooa.desktop_ui`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `find_objects` Find desktop objects |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DESKTOP_UI.BAD_OBJECT | UiResilienceTest, Studio UI test (capture panel) |
| `get_object_properties` Get desktop object properties |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DESKTOP_UI.BAD_OBJECT | UiResilienceTest, Studio UI test (capture panel) |
| `get_table` Get desktop table |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DESKTOP_UI.BAD_OBJECT | UiResilienceTest, Studio UI test (capture panel) |
| `object_action` Desktop object action | App integration/Capture scrollable text; App integration/Capture text of window | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DESKTOP_UI.BAD_OBJECT | UiResilienceTest, Studio UI test (capture panel) |
| `pick_object` Pick desktop object |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> STEP.TIMEOUT | UiResilienceTest, Studio UI test (capture panel) |
| `record` Record desktop actions |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> STEP.TIMEOUT | UiResilienceTest, Studio UI test (capture panel) |
| `wait_for_object` Wait for desktop object |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DESKTOP_UI.BAD_OBJECT | UiResilienceTest, Studio UI test (capture panel) |
| `object_exists (condition)` Desktop object exists |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test (needs a desktop application to capture) | UiResilienceTest, Studio UI test (capture panel) |

### Image recognition (`ooa.image`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `click_image` Click image |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `find_in_image` Find image in image | Image Recognition/Find window in window | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `find_on_screen` Find image on screen | Image Recognition/Find image in window | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `wait_for_image` Wait for image |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `wait_for_screen_change` Wait for screen change | Wait/Wait for screen change | New: AA's Wait for screen change (region relative to a window or the screen, initial wait, time-out) had no equivalent. | New action with mode change or settle (wait until the screen stops changing), threshold, window, initial wait, fail or carry on. | Done: 'settle' replaces fixed delays after loading. | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `image_in_image (condition)` Image is in image file |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |
| `image_on_screen (condition)` Image is on screen |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11, desktop), ImageOcrExcelDesktopPropertiesTest |

### OCR (`ooa.ocr`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `click_text` Click text on screen |  | Same options as documented in property-gaps; nothing further found. | Same memory cap as Read text from screen. |  | mock server: no request (checks inputs or session first) -> OCR.NOT_INSTALLED | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |
| `find_text_in_image` Find text in image |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> OCR.NOT_INSTALLED | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |
| `read_image` Read text from image | OCR/Capture text by path | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> OCR.NOT_INSTALLED | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |
| `read_screen` Read text from screen | OCR/Capture window; OCR/Capture area; App integration/Capture area | Defect found by the smoke test: a whole multi-monitor screen at scale 2 ran out of memory. | The enlarged image is capped at about 25 megapixels; scale limits corrected to 1-5 (what the code accepts). |  | mock server: no request (checks inputs or session first) -> OCR.NOT_INSTALLED | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |
| `read_url` Read text from image URL | OCR/Capture text by URL | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> OCR.BAD_URL | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |
| `text_on_screen (condition)` Text is on screen |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test (needs Tesseract) | OcrPgpTest, ImageOcrExcelDesktopPropertiesTest |

### Application (`ooa.application`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `open_file` Open file | File/Open | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 11), SystemGapsTest |
| `run_program` Run program and wait |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 11), SystemGapsTest |
| `start_program` Start program | Application/Open program/file | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 11), SystemGapsTest |
| `stop_program` Stop program |  | Same options as documented in property-gaps; nothing further found. | Process id and program name exclude each other in the form. |  | pass (1) | CommandSamplesTest (cli, 11), SystemGapsTest |
| `is_running (condition)` Program is running |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 11), SystemGapsTest |

## 7. Email

### Email (`ooa.email`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `connect` Connect to mailbox | Email/Connect | Checked in AA's editor: Outlook / email server / EWS, SSL, host, port, IMAP or POP3, credential user and password, time-out 120 s (10-300). Ours: email server only (Microsoft 365 through the Graph toolkit), time-out 15 s. | New smtp_host, smtp_port and smtp_security: the session keeps an outgoing server so Send, Reply and Forward need no server or sign-in of their own; time-out limited to 1-300 s. | Done. Proposal: a local-Outlook connection for desktops without IMAP access. | pass (in the move sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `delete` Delete email | Email/Delete | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `delete_all` Delete all matching emails | Email/Delete all | Defect found by the sample task: a folder already open in the session did not see emails moved into it later, so Delete all removed 1 of 3. | An open IMAP folder is refreshed (NOOP) each time a step uses it; the same fix applies to every mailbox action. |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `disconnect` Disconnect | Email/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `folder_exists` Check folder exists | Email/Check if folder exists | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `forward` Forward email | Email/Forward | Same options as documented in property-gaps; nothing further found. | Uses the session's outgoing server when the SMTP fields are empty; sign-in optional. |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `get` Read email |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `get_folders` Get folders |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `list` Find emails |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the move sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `mark` Mark read or unread | Email/Change status | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `move` Move email | Email/Move | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `move_all` Move all matching emails | Email/Move all | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `reply` Reply to email | Email/Reply | Same options as documented in property-gaps; nothing further found. | Uses the session's outgoing server when the SMTP fields are empty; sign-in optional. |  | pass (1) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `reply_all` Reply to all | Email/Reply All | Same options as documented in property-gaps; nothing further found. | Uses the session's outgoing server when the SMTP fields are empty; sign-in optional. |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `save_all_attachments` Save attachments of all matching emails | Email/Save all attachments | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `save_attachments` Save attachments | Email/Save attachments | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `save_email_as_eml` Save email as .eml | Email/Save email | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the reply sample) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |
| `send` Send email | Email/Send | Checked in AA's editor: recipients, reply-to, 'raise error if addresses invalid', importance, subject, attachments, body, then SMTP host and port, 'server requires authentication' and credentials on every send. Ours always signed in and always failed on invalid addresses (kept: safer). | SMTP host and user are optional when the mailbox session has an outgoing server; with no password or token the server is used without signing in (internal relay). |  | pass (5) | CommandSamplesTest (cli, 12-email.json: in-memory SMTP/IMAP server), EmailMessagesTest, EmailOptionsTest |

## 8. Word, PowerPoint, PDF

### Word (no Office needed) (`ooa.word`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_paragraph` Add paragraph |  | Defect found by the sample task: a new document started with an empty paragraph, so generated documents began with a blank line and paragraph numbers were off by one. | The first paragraph added to a new document uses the existing blank paragraph. |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `add_table` Insert table | Word/Insert table | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `close` Close | Word/Close | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `create_document` Create document | Word/Create document | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `find_replace_picture` Find and replace picture | Word/Find and replace picture | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `format_text` Format text | Word/Format text | AA takes the colour as a decimal number; ours takes hex. AA limits size to 1-1638. | Paragraph range as for Replace text; size limited to 1-1638. |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `insert_picture` Insert picture | Word/Insert picture | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `insert_text` Insert text | Word/Insert text | Checked in AA's editor: at the cursor, start, end or a bookmark; text plain or HTML. Ours: start, end, bookmark, after or before a text; plain text with formatting options. | Writing into a new document no longer leaves a blank first paragraph. | Proposal: HTML input (bold, italic, lists) converted to Word formatting. | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `open` Open document | Word/Open | Checked in AA's editor: file, read-write or read-only, open password, edit password, session. Ours: same except the edit password (not a security feature). |  |  | pass (2) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `read_tables` Read tables | Word/Read table | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `read_text` Read text | Word/Read text | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `replace_text` Replace text | Word/Find and replace text | Checked in AA's editor: match case, whole words, range (full document, a page or a paragraph index), occurrence. Ours had no range. | New from_paragraph / to_paragraph range (1-based body paragraphs). | Page ranges need Word's own layout engine (not available without Word). | pass (1) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `save` Save | Word/Save | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the open sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |
| `save_as` Save as | Word/Save as | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_document sample) | CommandSamplesTest (cli, 13-word-powerpoint-pdf.json), WordGapsTest, WordPackageTest |

### PowerPoint (`ooa.powerpoint`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_slide` Add slide | PowerPoint/Add slide | Checked in AA's editor: layout and position; ours also sets title and body text. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `close` Close | PowerPoint/Close | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `copy_slide` Copy slide | PowerPoint/Copy paste slide | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `create_presentation` Create presentation | PowerPoint/Create presentation | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `delete_slide` Delete slide | PowerPoint/Delete slide | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `find_replace_picture` Find and replace picture | PowerPoint/Find and replace picture | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `find_replace_text` Find and replace text | PowerPoint/Find and replace text | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `format_text` Format text | PowerPoint/Format text | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `insert_picture` Insert picture | PowerPoint/Insert picture | Same options as documented in property-gaps; nothing further found. | Slide is optional (last slide). |  | pass (1) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `insert_table` Insert table | PowerPoint/Insert table | Same options as documented in property-gaps; nothing further found. | Slide is optional (last slide). |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `insert_text` Insert text | PowerPoint/Insert text | Checked in AA's editor: inserts on the current slide by default, position in centimetres, formatting. Ours needed a slide number on every insert and uses points. | Slide is optional: the last slide (usually the one just added) when empty. |  | pass (1) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `open` Open presentation | PowerPoint/Open | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `read_text` Read text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `save` Save | PowerPoint/Save | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `save_as` Save as | PowerPoint/Save as | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |
| `slide_count` Get slide count |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the create_presentation sample) | CommandSamplesTest (cli, 13), PowerPointPackageTest |

### PDF (`ooa.pdf`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `decrypt` Decrypt PDF | PDF/Decrypt document | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `encrypt` Encrypt PDF | PDF/Encrypt document | Passwords must be vault credentials (plain text refused): safer than AA's 'insecure string' option. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `extract_area_text` Extract text from area |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `extract_images` Extract images | PDF/Extract image | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `extract_text` Extract text | PDF/Extract text | Checked in AA's editor: user and owner passwords, plain or structured text, page range, save to .txt, overwrite, properties to a dictionary. Ours: same (properties via Get document properties). |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `fill_form` Fill form fields |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `get_properties` Get document properties | PDF/Get property | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `merge` Merge PDFs | PDF/Merge documents | AA's PDFUtils package also merges every PDF in a folder; ours needed a list. | New folder, pattern and sort_by inputs; output files lists what was merged; the target is never merged into itself. |  | pass (1) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `page_count` Get page count |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (2) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `read_form` Read form fields | PDF/Extract field | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `split` Split PDF | PDF/Split document | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |
| `to_images` Convert to images |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the page_count sample) | CommandSamplesTest (cli, 13: a hand-written two-page PDF), PdfPackageTest |

### Excel (desktop, needs Excel) (`ooa.excel_desktop`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `convert` Convert workbook |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | ImageOcrExcelDesktopPropertiesTest |
| `export_pdf` Export to PDF | Excel advanced/Convert excel to PDF | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> EXCEL_DESKTOP.FAILED | ImageOcrExcelDesktopPropertiesTest |
| `refresh_all` Refresh all data |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | ImageOcrExcelDesktopPropertiesTest |
| `run_macro` Run macro | Excel advanced/Run macro | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> EXCEL_DESKTOP.FAILED | ImageOcrExcelDesktopPropertiesTest |
| `update_links` Update workbook links |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | ImageOcrExcelDesktopPropertiesTest |

## 9. System and local toolkits

### System (`ooa.system`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get_environment_variable` Get environment variable | System/Get environment variable | Defect found by the sample task: a value set with Set environment variable (process scope) was invisible to Get environment variable in the same run. | Values set earlier in the run are returned first. |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `get_environment_variables` Get all environment variables |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `get_system_info` Get system info |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `lock` Lock computer | System/Lock computer | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `log_off` Sign out | System/Logoff | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `restart` Restart computer | System/Restart | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `set_environment_variable` Set environment variable |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |
| `shut_down` Shut down computer | System/Shutdown | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14-system-local.json), SystemGapsTest |

### Windows services (`ooa.services`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get_status` Get service status | Service/Get service status | Returns upper-case states (RUNNING, STOPPED). |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `pause` Pause service | Service/Pause service | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), SystemGapsTest |
| `restart` Restart service |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), SystemGapsTest |
| `resume` Resume service | Service/Resume service | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), SystemGapsTest |
| `start` Start service | Service/Start service | An unknown service gave the generic SERVICES.FAILED. | Unknown services fail with SERVICES.NOT_FOUND (start, stop, pause, resume, restart), as Get status already did; wait time shows only when waiting. |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `stop` Stop service | Service/Stop service | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), SystemGapsTest |
| `is_running (condition)` Service is running |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |

### Printer (`ooa.printer`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get_default` Get default printer | Printer/Get default | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `list_printers` Get printers |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `print_file` Print file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `remove_printer` Remove printer | Printer/Remove | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `set_default` Set default printer | Printer/Set default | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |

### Network (`ooa.network`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `check_port` Check port |  | Same options as documented in property-gaps; nothing further found. | Port limited to 1-65535 (checked before the run); time-outs at least 1 ms. |  | pass (2) | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `ping` Ping |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `snmp_get` SNMP get | SNMP/Get | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `snmp_get_next` SNMP get next | SNMP/Get next | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `snmp_send_trap` SNMP send trap | SNMP/Send trap | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `snmp_set` SNMP set | SNMP/Set | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `snmp_walk` SNMP walk | SNMP/Walk | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `host_reachable (condition)` Host is reachable |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesBatch2Test |
| `port_open (condition)` Port is open |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesBatch2Test |

### Scripts (`ooa.script`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `run_javascript` Run JavaScript | JavaScript/Run JavaScript | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_javascript_function` Run JavaScript function | JavaScript/Open | Same options as documented in property-gaps; nothing further found. | Typed script as an alternative to a file. |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_powershell` Run PowerShell |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_python` Run Python | Python script/Execute script | Same options as documented in property-gaps; nothing further found. | Script and file exclude each other in the form; time-out at least 1 s. |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_python_function` Run Python function | Python script/Open; Python script/Execute function | Checked in AA's editor: AA opens a Python session from a file or a typed script, runs a function with one argument, errors optional. Ours: a function from a file, any number of arguments and keyword arguments, typed result, errors on by default. | New script input: the function's code can be typed instead of a file (also for JavaScript and VBScript functions); script and file exclude each other in the form. |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_vbscript` Run VBScript |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `run_vbscript_function` Run VBScript function | VBScript/Open; VBScript/Run function | Same options as documented in property-gaps; nothing further found. | Typed script as an alternative to a file. |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |

### Hash, encryption and encoding (`ooa.crypto`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `base64_decode` Base64 decode |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `base64_encode` Base64 encode |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `decrypt_file` Decrypt file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the encrypt_text sample) | CommandSamplesTest (cli, 14), PackagesTest |
| `decrypt_text` Decrypt text |  | Decrypted text is returned as a secret (masked in logs and outputs), so it never leaks into run history. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `encrypt_file` Encrypt file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the encrypt_text sample) | CommandSamplesTest (cli, 14), PackagesTest |
| `encrypt_text` Encrypt text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `hash_file` Hash file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `hash_text` Hash text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `hmac` HMAC signature |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `rsa_decrypt` RSA decrypt text |  | Decrypted text is returned as a secret (masked). |  | Proposal: take private keys from the vault instead of a file path. | pass (in the rsa_generate_keys sample) | CommandSamplesTest (cli, 14), PackagesTest |
| `rsa_encrypt` RSA encrypt text |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the rsa_generate_keys sample) | CommandSamplesTest (cli, 14), PackagesTest |
| `rsa_generate_keys` Generate RSA key pair |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), PackagesTest |
| `rsa_sign` RSA sign |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the rsa_generate_keys sample) | CommandSamplesTest (cli, 14), PackagesTest |
| `rsa_verify` RSA verify |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the rsa_generate_keys sample) | CommandSamplesTest (cli, 14), PackagesTest |

### PGP (`ooa.pgp`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `create_keys` Create key pair | PGP/Create keys | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NOT_INSTALLED | OcrPgpTest, PropertyGapsTest, SystemGapsTest |
| `decrypt` Decrypt file | PGP/Decrypt files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NOT_INSTALLED | OcrPgpTest, PropertyGapsTest, SystemGapsTest |
| `encrypt` Encrypt file | PGP/Encrypt files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NO_RECIPIENT | OcrPgpTest, PropertyGapsTest, SystemGapsTest |
| `import_key` Import key |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NOT_INSTALLED | OcrPgpTest, PropertyGapsTest, SystemGapsTest |
| `sign` Sign file |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NOT_INSTALLED | OcrPgpTest, PropertyGapsTest, SystemGapsTest |
| `verify` Verify signature |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> PGP.NOT_INSTALLED | OcrPgpTest, PropertyGapsTest, SystemGapsTest |

### Log to file (`ooa.log`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `log_to_command_center` Log to Command Center | Logging/Log to Server | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `log_variables` Log variables to file | Logging/Log variables to file | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |
| `write` Log to file | Logging/Log text to file | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), SystemGapsTest |

### Play sound (`ooa.sound`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `beep` Beep | Play Sound/Play beep | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 14, desktop) |
| `play` Play sound file | Play Sound/Play media file | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14, desktop) |

### DLL (`ooa.dll`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `call` Call DLL function | DLL/Run function | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: kernel32 calls) |
| `close` Close DLL | DLL/Close | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: kernel32 calls) |
| `open` Open DLL | DLL/Open | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: kernel32 calls) |

### Windows Credential Manager (`ooa.credential_manager`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `delete_credential` Remove credential |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14, desktop: writes and removes a test entry) |
| `list_targets` List credential targets |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | CommandSamplesTest (cli, 14, desktop: writes and removes a test entry) |
| `read_credential` Read credential | Credential Manager/Read Credential | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14, desktop: writes and removes a test entry) |
| `write_credential` Save credential |  | Same options as documented in property-gaps; nothing further found. |  |  | written; skipped in this run (needs a browser or desktop) | CommandSamplesTest (cli, 14, desktop: writes and removes a test entry) |
| `credential_exists (condition)` Credential exists |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14, desktop: writes and removes a test entry) |

### Fuzzy Match (`ooa.fuzzy`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `best_match` Find closest match | Fuzzy Match/Fuzzy Match | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |
| `fuzzy_lookup` Fuzzy join tables |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |
| `similarity` Text similarity |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |
| `texts_similar (condition)` Texts are similar |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |

### Interrupt handlers (`ooa.interrupts`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_rule` Add interrupt rule |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), UiResilienceTest |
| `check_now` Check interrupt rules now |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_rule sample) | CommandSamplesTest (cli, 14), UiResilienceTest |
| `clear_rules` Clear interrupt rules |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_rule sample) | CommandSamplesTest (cli, 14), UiResilienceTest |
| `list_rules` List interrupt rules |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_rule sample) | CommandSamplesTest (cli, 14), UiResilienceTest |
| `remove_rule` Remove interrupt rule |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_rule sample) | CommandSamplesTest (cli, 14), UiResilienceTest |

### Database (`ooa.database`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `begin_transaction` Begin transaction | Database/Begin database transaction | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `bulk_insert` Bulk insert | Database/Bulk insert | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `call_procedure` Run stored procedure | Database/Run stored procedure | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `commit` Commit | Database/End database transaction | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `connect` Connect | Database/Connect | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `describe_table` Describe table |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `disconnect` Disconnect | Database/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `execute` Insert/update/delete | Database/Insert/Update/Delete | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `export_to_file` Export to CSV file | Database/Export to file stream | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `list_tables` List tables |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `query` Read from (query) | Database/Read from; Database/Export to data table | Same options as documented in property-gaps; nothing further found. | Limits on rows and time-outs. |  | pass (1) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |
| `rollback` Roll back |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 14: in-memory H2), DatabasePackageTest |

### REST Web Service (`ooa.rest`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `download_file` Download file | REST Web Services/Get file stream | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local HTTP server), PackagesTest |
| `request` Send request | REST Web Services/Delete method; REST Web Services/Get method; REST Web Services/Patch method; REST Web Services/Post method; REST Web Services/Put method | Checked in AA's editor (Post method): URI, proxy, authentication, headers, content type, body with substitutions, failure response, insecure TLS, accept cookies, time-out, client keystore, output. Ours has all of these. | Body fields hidden for GET; JSON, form and raw body exclude each other; proxy sign-in only with a proxy; certificate password only with a certificate. |  | pass (3) | CommandSamplesTest (cli, 14: local HTTP server), PackagesTest |
| `upload_file` Upload file |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local HTTP server), PackagesTest |

### SOAP Web Service (`ooa.soap`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `send` Send SOAP request | SOAP Web Service/SOAP Web Service | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local HTTP server) |

### FTP / SFTP (`ooa.file_transfer`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `change_folder` Change folder | Ftp/Sftp/Change folder | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | FileTransferFoldersTest (embedded servers) |
| `connect` Connect | Ftp/Sftp/Connect | Same options as documented in property-gaps; nothing further found. | SFTP-only options (private key, passphrase, host key) and FTP-only options (passive mode, TLS mode, transfer mode) show only for their protocol; port 1-65535. |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `create_folder` Create folder | Ftp/Sftp/Create folder | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `delete` Delete |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `delete_files` Delete files | Ftp/Sftp/Delete files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | FileTransferFoldersTest (embedded servers) |
| `delete_folder` Delete folder | Ftp/Sftp/Delete folder | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | FileTransferFoldersTest (embedded servers) |
| `disconnect` Disconnect | Ftp/Sftp/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | FileTransferFoldersTest (embedded servers) |
| `download` Download | Ftp/Sftp/Get files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `download_folder` Download folder | Ftp/Sftp/Get folders | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | FileTransferFoldersTest (embedded servers) |
| `list` List files |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `rename` Rename or move | Ftp/Sftp/Rename files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TRANSFER.FAILED | FileTransferFoldersTest (embedded servers) |
| `upload` Upload | Ftp/Sftp/Put files | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | FileTransferFoldersTest (embedded servers) |
| `upload_folder` Upload folder | Ftp/Sftp/Put folders | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FILE.NOT_FOUND | FileTransferFoldersTest (embedded servers) |

### Currency (`ooa.currency`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `convert` Convert amount | Currency Conversion/Historical Conversion; Currency Conversion/Real Time Conversion | Same options as documented in property-gaps; nothing further found. | Address and rates path show only for a JSON source; fixed rates only for the fixed source; decimals 0-10. |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |
| `get_rate` Get exchange rate |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |
| `list_currencies` List currencies |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14), FuzzyCurrencyTest |

### Work queues (`ooa.workload`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_item` Add work item | Workload/Insert work item | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `add_items` Add work items from table |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `complete` Mark completed |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `counts` Get queue counts |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `defer` Defer item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `fail` Mark failed |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `get_item` Get work item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `get_next` Get next item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `list_items` List items |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `update_item` Update work item | Workload/Update work item | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the add_item sample) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `has_items (condition)` Queue has items |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |
| `each_item (iterator)` Each available work item |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14: local queue), AiAndWorkTest |

### Insights (`ooa.insights`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `close_transaction` Close transaction |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the tag_values sample) | CommandSamplesTest (cli, 14) |
| `open_transaction` Open transaction |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the tag_values sample) | CommandSamplesTest (cli, 14) |
| `read_values` Read business values |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the tag_values sample) | CommandSamplesTest (cli, 14) |
| `tag_values` Tag business values |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 14) |

### Trigger Loop (`ooa.triggerloop`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `process_running (condition)` Program is running |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | TriggerLoopTest |
| `file_arrives (iterator)` Each new file in folder |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | TriggerLoopTest |
| `process_starts (iterator)` Each time a program starts |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | TriggerLoopTest |
| `timer_tick (iterator)` Every interval |  | Same options as documented in property-gaps; nothing further found. |  |  | unit test | TriggerLoopTest |

## 10. Cloud connectors

### Microsoft 365 (`ooa.microsoft365`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_attendees` Add event attendees | Microsoft 365 Calendar/Add attendee | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/events/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_event_attachment` Add event attachment | Microsoft 365 Calendar/Add attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/events/x/attachments -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `cancel_event` Cancel calendar event | Microsoft 365 Calendar/Cancel meeting | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/events/x/cancel -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `check_permissions` Check file permission (OneDrive/SharePoint) | Microsoft 365 OneDrive/Check permission | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/permissions -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `copy_item` Copy file or folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Copy file or folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_chat` Create one-on-one chat (Teams) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_event` Create calendar event | Microsoft 365 Calendar/Create meeting | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_TIMES | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_folder` Create folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Create folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/drive/root/children -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_list_item` Create SharePoint list item |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /sites/x/lists/x/items -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_sharing_link` Create sharing link (OneDrive/SharePoint) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/drive/root:/x:/createLink -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_all_mail` Delete all emails (Outlook) | Microsoft 365 Outlook/Delete all | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/mailFolders/inbox/messages -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_event` Delete calendar event |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /me/events/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_item` Delete file or folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Delete file or folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /me/drive/root:/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_list_item` Delete SharePoint list item |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /sites/x/lists/x/items/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_mail` Delete email (Outlook) | Microsoft 365 Outlook/Delete | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/messages/x/move -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_file` Download file (OneDrive/SharePoint) | Microsoft 365 OneDrive/Download file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/content -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_add_table_rows` Add table rows (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.NO_VALUES | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_add_worksheet` Add worksheet (Excel online) | Microsoft 365 Excel/Create sheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/drive/root:/x:/workbook/worksheets/add -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_autofit_columns` Autofit columns (Excel online) | Microsoft 365 Excel/Autofit columns | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_autofit_rows` Autofit rows (Excel online) | Microsoft 365 Excel/Autofit rows | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_clear_range` Clear range (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_create_table` Create table (Excel online) | Microsoft 365 Excel/Create table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_create_workbook` Create workbook (Excel online) | Microsoft 365 Excel/Create workbook | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_INPUT | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_delete_range` Delete range (Excel online) | Microsoft 365 Excel/Delete cell; Microsoft 365 Excel/Delete range; Microsoft 365 Excel/Delete row or column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_delete_table` Delete table (Excel online) | Microsoft 365 Excel/Delete table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /me/drive/root:/x:/workbook/tables/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_delete_worksheet` Delete worksheet (Excel online) | Microsoft 365 Excel/Delete worksheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /me/drive/root:/x:/workbook/worksheets('x') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_find` Find in sheet (Excel online) | Microsoft 365 Excel/Find | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/worksheets('x')/usedRange(valuesOnly=true) -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_find_next_empty_cell` Find next empty cell (Excel online) | Microsoft 365 Excel/Find next empty cell; Microsoft 365 Excel/Go to next empty cell | New: AA Microsoft 365 Excel Find / Go to next empty cell. | New action: first empty cell down a column or along a row. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_format_range` Format range (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_get_cell` Get cell (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_get_cell_format` Read cell format (Excel online) | Microsoft 365 Excel/Format cell; Microsoft 365 Excel/Read cell format; Microsoft 365 Excel/Get cell or text color | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_get_range` Get range values (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_get_table_rows` Get table rows (Excel online) | Microsoft 365 Excel/Get table row; Microsoft 365 Excel/Get table row count | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/tables/x/headerRowRange -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_get_worksheets` Get worksheets (Excel online) | Microsoft 365 Excel/Get worksheet names; Microsoft 365 Excel/Retrieve sheet count | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/worksheets -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_hide_worksheet` Hide worksheet (Excel online) | Microsoft 365 Excel/Hide worksheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/worksheets('x') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_insert_range` Insert range (Excel online) | Microsoft 365 Excel/Insert cell; Microsoft 365 Excel/Insert range; Microsoft 365 Excel/Insert row or column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_list_tables` List tables (Excel online) | Microsoft 365 Excel/Get table names | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/tables -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_read_column` Read column (Excel online) | Microsoft 365 Excel/Get column name; Microsoft 365 Excel/Get table column; Microsoft 365 Excel/Read column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/worksheets('x')/usedRange(valuesOnly=true) -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_read_formula` Read formula (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_read_range` Read Excel range (OneDrive/SharePoint) | Microsoft 365 Excel/Read cell formula; Microsoft 365 Excel/Get worksheet as data table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_read_row` Read row (Excel online) | Microsoft 365 Excel/Get row count; Microsoft 365 Excel/Get row number; Microsoft 365 Excel/Read row | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/worksheets('x')/usedRange(valuesOnly=true) -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_rename_table` Rename table (Excel online) | Microsoft 365 Excel/Rename table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/tables/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_rename_worksheet` Rename worksheet (Excel online) | Microsoft 365 Excel/Rename worksheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/worksheets('x') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_set_cell` Set cell (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_set_formula` Set formula (Excel online) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.BAD_RANGE | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_set_range` Set range values (Excel online) | Microsoft 365 Excel/Set cell; Microsoft 365 Excel/Set cell color; Microsoft 365 Excel/Set cell formula | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.NO_VALUES | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_show_worksheet` Show worksheet (Excel online) | Microsoft 365 Excel/Copy worksheet; Microsoft 365 Excel/Show worksheet; Microsoft 365 Excel/Switch to sheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/worksheets('x') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_used_range` Get used range (Excel online) | Microsoft 365 Excel/Get cell; Microsoft 365 Excel/Get multiple cells; Microsoft 365 Excel/Get cell address | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/workbook/worksheets('x')/usedRange(valuesOnly=true) -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_write_data_table` Write Data Table (Excel online) | Microsoft 365 Excel/Write from data table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/worksheets('x')/range(address='A1:A2') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `excel_write_range` Write Excel range (OneDrive/SharePoint) | Microsoft 365 Excel/Go to cell; Microsoft 365 Excel/Paste cell | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x:/workbook/worksheets/('x')/range(address='A1:A2') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `export_to_pdf` Export file to PDF (OneDrive/SharePoint) | Microsoft 365 OneDrive/Export to PDF | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/content -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `find_items` Find files (OneDrive/SharePoint) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root/search(q='x') -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `find_meeting_times` Find free meeting times | Microsoft 365 Calendar/Get available meeting slot(s) | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `forward_mail` Forward email (Outlook) | Microsoft 365 Outlook/Forward | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/messages/x/forward -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_channel_by_name` Get channel by name (Teams) | Microsoft Teams/Get channel by name | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /teams/x/channels -> M365.NOT_FOUND | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_channel_details` Get channel details (Teams) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /teams/x/channels/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_item_info` Get file information (OneDrive/SharePoint) | Microsoft 365 OneDrive/Get file or folder information | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_list_items` Get SharePoint list items |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /sites/x/lists/x/items -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_mail` Read email (Outlook) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/messages/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_team_by_name` Get team by name (Teams) | Microsoft Teams/Get team by name | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/joinedTeams -> M365.NOT_FOUND | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_team_details` Get team details (Teams) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /teams/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user` Get user (Microsoft 365) | Microsoft Teams/Get user details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_channels` List channels (Teams) | Microsoft Teams/Get channel details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /teams/x/channels -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_events` Get calendar events |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/calendarView -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_files` List files (OneDrive/SharePoint) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root/children -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_lists` List SharePoint lists |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /sites/x/lists -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_mail` Find emails (Outlook) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (2) | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_permissions` List file permissions (OneDrive/SharePoint) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/permissions -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_teams` List teams (Teams) | Microsoft Teams/Get team details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/joinedTeams -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `mail_folder_exists` Check mail folder exists (Outlook) | Microsoft 365 Outlook/Check if folder exists | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/mailFolders -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `mark_mail` Change email status (Outlook) | Microsoft 365 Outlook/Change status | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/messages/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_all_mail` Move all emails (Outlook) | Microsoft 365 Outlook/Move all | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/mailFolders/inbox/messages -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_item` Move file or folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Move file or folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_mail` Move email (Outlook) | Microsoft 365 Outlook/Move | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/messages/x/move -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_attendees` Remove event attendees | Microsoft 365 Calendar/Delete attendee; Microsoft 365 Calendar/Delete meeting information | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/events/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_item` Rename file or folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Rename file or folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /me/drive/root:/x -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `reply_all_mail` Reply all to email (Outlook) | Microsoft 365 Outlook/Reply All | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/messages/x/replyAll -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `reply_mail` Reply to email (Outlook) | Microsoft 365 Outlook/Reply | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/messages/x/reply -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `respond_to_event` Respond to meeting invitation | Microsoft 365 Calendar/Respond to meeting | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/events/x/accept -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `restore_version` Restore file version (OneDrive/SharePoint) | Microsoft 365 OneDrive/Restore last version | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/drive/root:/x:/versions -> M365.NO_OLDER_VERSION | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `save_all_mail_attachments` Save attachments of all matching emails (Outlook) | Microsoft 365 Outlook/Save all attachments | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/mailFolders/inbox/messages -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `save_mail_as_eml` Save email as EML (Outlook) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/messages/x/$value -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `save_mail_attachments` Save email attachments (Outlook) | Microsoft 365 Outlook/Assign attachment(s); Microsoft 365 Outlook/Save attachments; Microsoft 365 Outlook/Save email | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /me/messages/x/attachments -> FILE.IO_ERROR | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_chat_message` Send chat message (Teams) | Microsoft Teams/Send chat message | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /chats/x/messages -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_mail` Send email (Outlook) | Microsoft 365 Outlook/Send | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `share_item` Share file or folder (OneDrive/SharePoint) | Microsoft 365 OneDrive/Find files and folders; SharePoint/Folder & file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /me/drive/root:/x:/invite -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `teams_message` Post Teams message | Microsoft Teams/Send channel message | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /teams/x/channels/x/messages -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_event` Update calendar event | Microsoft 365 Calendar/Modify meeting | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> M365.NOTHING_TO_DO | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_list_item` Update SharePoint list item | SharePoint/List & list item | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /sites/x/lists/x/items/x/fields -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_file` Upload file (OneDrive/SharePoint) | Microsoft 365 OneDrive/Assign file; Microsoft 365 OneDrive/Upload file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PUT /me/drive/root:/x:/content -> finished | CommandSamplesTest (cli, 15-connectors.json), Microsoft365GapsTest, Microsoft365OptionsTest, ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Google Workspace (`ooa.google`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `calendar_create_event` Create Google Calendar event | Google Calendar/Create event | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /calendar/v3/calendars/primary/events -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `calendar_delete_event` Delete Google Calendar event | Google Calendar/Delete event | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /calendar/v3/calendars/primary/events/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `calendar_list_events` List Google Calendar events |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `calendar_update_event` Update Google Calendar event |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_check_permission` Check Drive permission | Google Drive/Check Permissions | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x/permissions -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_copy_file` Copy Drive file | Google Drive/Copy file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /drive/v3/files/x/copy -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_copy_folder` Copy Drive folder | Google Drive/Copy folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_create_folder` Create Drive folder | Google Drive/Create Folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /drive/v3/files -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_create_permission` Share Drive item | Google Drive/Create file permission | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_delete_item` Delete Drive item | Google Drive/Delete file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /drive/v3/files/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_delete_permission` Remove Drive permission | Google Drive/Delete file permission; Google Drive/Delete Folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /drive/v3/files/x/permissions/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_download` Download from Drive |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_download_export` Export Google file | Google Drive/Download file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x/export -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_find_items` Find Drive items | Google Drive/Find file/folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_get_file_info` Get Drive item info |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_list` List Drive files | Google Drive/Get file information | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_list_permissions` List Drive permissions | Google Drive/Get file permission | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x/permissions -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_move_item` Move Drive item | Google Drive/Move file; Google Drive/Move folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /drive/v3/files/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_rename_item` Rename Drive item | Google Drive/Assign File; Google Drive/Rename file; Google Drive/Rename folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /drive/v3/files/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_update_permission` Change Drive permission |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /drive/v3/files/x/permissions/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `drive_upload` Upload to Drive | Google Drive/Upload file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /upload/drive/v3/files -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_delete_all` Delete all matching Gmail messages | Gmail/Delete all | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_delete_message` Delete Gmail message | Gmail/Delete email | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /gmail/v1/users/me/messages/x/trash -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_forward_message` Forward Gmail message | Gmail/Forward | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_get_message` Read Gmail message |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_label_exists` Check Gmail label exists |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/labels -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_list_messages` List Gmail messages |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_mark_message` Change Gmail message status | Gmail/Change status | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /gmail/v1/users/me/messages/x/modify -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_move_all` Move all matching Gmail messages | Gmail/Move all | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.BAD_INPUT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_move_message` Move Gmail message | Gmail/Move email | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/labels -> GOOGLE.NO_LABEL | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_reply_all` Reply all to Gmail message | Gmail/Reply all | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> GOOGLE.NO_RECIPIENT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_reply_message` Reply to Gmail message | Gmail/Reply | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> GOOGLE.NO_RECIPIENT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_save_all_attachments` Save attachments of all matching Gmail messages | Gmail/Save all attachments | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_save_attachments` Save Gmail attachments | Gmail/Save attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_save_message_as_eml` Save Gmail message as .eml | Gmail/Save email | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /gmail/v1/users/me/messages/x -> GOOGLE.NO_CONTENT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `gmail_send` Send Gmail | Gmail/Send | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /gmail/v1/users/me/messages/send -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_add_sheet` Add sheet |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /v4/spreadsheets/x:batchUpdate -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_append` Append rows to sheet |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /v4/spreadsheets/x/values/x:append -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_autofit_columns` Autofit columns | Google Sheets/Autofit columns | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_autofit_rows` Autofit rows | Google Sheets/Autofit rows | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_clear_range` Clear range |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /v4/spreadsheets/x/values/x:clear -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_copy_range` Copy range |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_create_spreadsheet` Create spreadsheet | Google Sheets/Open spreadsheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /v4/spreadsheets -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_delete_columns` Delete columns |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_delete_range` Delete range | Google Sheets/Delete cell; Google Sheets/Delete range | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_delete_rows` Delete rows | Google Sheets/Delete Row/Column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_delete_sheet` Delete sheet | Google Sheets/Delete sheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_find_cells` Find cells |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_format_cells` Format cells | Google Sheets/Set cell/text color; Google Sheets/Format cells | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GOOGLE.NOTHING_TO_FORMAT | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_get_cell` Get cell value |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_get_cell_color` Get cell colour | Google Sheets/Get cell/text color | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_get_range` Get range values |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_hide_sheet` Hide sheet | Google Sheets/Hide worksheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_insert_columns` Insert columns |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_insert_range` Insert range | Google Sheets/Insert cell; Google Sheets/Insert range | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_insert_rows` Insert rows | Google Sheets/Create sheet; Google Sheets/Create workbook; Google Sheets/Insert Row/Column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_list_sheets` List sheets | Google Sheets/Get All Sheet Names | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_read` Read sheet range | Google Sheets/Get multiple cells; Google Sheets/Get single cell | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_read_column` Read column | Google Sheets/Read column | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_read_formula` Read formula | Google Sheets/Read cell formula | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_read_row` Read row | Google Sheets/Read row | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_rename_sheet` Rename sheet | Google Sheets/Rename sheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_row_count` Get used row count | Google Sheets/Get number of rows; Google Sheets/Retrieve sheets count | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_set_cell` Set cell value | Google Sheets/Go to cell; Google Sheets/Paste cell; Google Sheets/Set cell | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PUT /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_set_formula` Set formula | Google Sheets/Set cell formula | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PUT /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_show_sheet` Show sheet | Google Sheets/Show worksheet | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_write` Write to sheet |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PUT /v4/spreadsheets/x/values/x -> finished | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sheets_write_data_table` Write data table | Google Sheets/Write from data table | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v4/spreadsheets/x -> GOOGLE.NO_SHEET | CommandSamplesTest (cli, 15), GoogleGapsTest, GoogleOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Jira (`ooa.jira`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_comment` Add comment | Jira/Add comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `attach_file` Attach file |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_issue` Create issue | Jira/Create issue | Checked in AA's editor: project key, issue type, summary (up to 255 characters), description, priority (Medium by default), assignee email, labels, components, custom fields; output key, id, address and full reply. Ours: same plus due date and parent issue; priority left to the project default. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_project` Create project | Jira/Create project | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.NO_LEAD | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_comment` Delete comment | Jira/Delete comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_issue` Delete issue | Jira/Delete issue | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_project` Delete project | Jira/Delete project | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_PROJECT_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_comments` Get comments | Jira/Get comments | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_issue` Get issue |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_projects` Get projects | Jira/Get projects | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /rest/api/2/project -> finished | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search` Search issues (JQL) | Jira/Get issue | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `transition` Move issue (transition) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_comment` Update comment | Jira/Update comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.BAD_KEY | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_issue` Update issue | Jira/Update issue | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.NO_FIELDS | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_project` Update project | Jira/Update project | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> JIRA.NO_FIELDS | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### ServiceNow (`ooa.servicenow`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_comment` Add comment or work note |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `attach_file` Attach file |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `authenticate` Authenticate |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FLOW.INVALID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_record` Create record |  | Same options as documented in property-gaps; nothing further found. | Address pattern accepts http://localhost for test servers (https still required for real instances). | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_attachment` Delete attachment |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_record` Delete record |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_attachment` Download attachment |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_record` Get record |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_attachments` List attachments | ServiceNow/Attachments | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_changed_records` List changed records |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_TIME | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `query` Query records |  | Checked in AA's editor (Get Multiple Records): table, fields, filter by name/value pairs or a query, limit (0 = all). Ours needed ServiceNow's encoded query syntax. | New filter input: field to value pairs, no query syntax needed (combined with query when both are given); local http test servers accepted. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/now/table/x -> finished | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `revoke_authentication` Revoke authentication | ServiceNow/Authentication | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FLOW.INVALID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_record` Update record | ServiceNow/Records | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SERVICENOW.BAD_SYS_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Salesforce (`ooa.salesforce`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `authenticate` Authenticate |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FLOW.INVALID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_record` Create record | Salesforce/Insert record | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_record` Delete record | Salesforce/Delete record | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SALESFORCE.BAD_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_attachments` Get attachments | Salesforce/Get attachment(s) | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SALESFORCE.BAD_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_record` Get record |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SALESFORCE.BAD_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `query` Query (SOQL) | Salesforce/Execute SOQL; Salesforce/Get record | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `revoke_authentication` Revoke authentication | Salesforce/Authentication; Salesforce/Revoke Authentication | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /services/oauth2/revoke -> finished | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_record` Update record | Salesforce/Update record | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SALESFORCE.BAD_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_attachment` Upload attachment | Salesforce/Upload file attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SALESFORCE.BAD_ID | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upsert_by_external_id` Upsert by external id | Salesforce/Upsert record (using External ID) | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /services/data/v61.0/sobjects/x/x/x -> finished | CommandSamplesTest (cli, 15), ConnectorsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Slack (`ooa.slack`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `get_channel_details` Get channel details |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /conversations.info -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_channel_id_by_name` Get channel id by name | Slack/Get channel ID by name | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /conversations.list -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_conversation_history` Get conversation history | Slack/Get channel details; Slack/Get conversation details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /conversations.history -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user_details` Get user details | Slack/Get user details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SLACK.BAD_USER | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_workspace_details` Get workspace details |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /team.info -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_workspace_id_by_name` Get workspace id by name | Slack/Get workspace ID by name; Slack/Get workspace details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /auth.teams.list -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `post_message` Post message |  | Defect found by the connector smoke test: a reply that is not a Slack answer gave the code SLACK.NULL. | Such replies fail with SLACK.BAD_RESPONSE and a hint about base_url or a proxy (all Slack commands). | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (3) | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_direct_message` Send direct message | Slack/Send channel message; Slack/Send direct message | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> SLACK.BAD_USER | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_message` Update message |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /chat.update -> SLACK.BAD_RESPONSE | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_file` Upload file |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FILE.NOT_FOUND | CommandSamplesTest (cli, 15), SlackOptionsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Workday (`ooa.workday`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `authenticate` Authenticate | Workday/Authentication | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get` Call REST endpoint (GET) |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_integration_event` Get integration event | Workday/Get Integration Event | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_report` Get report (RaaS) | Workday/Get Report | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `launch_integration` Launch integration | Workday/Launch EIB | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search_workers` Search workers |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> FLOW.INVALID | EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Asana (`ooa.asana`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_comment` Add comment | Asana/Create comment on task | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_followers` Add followers |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_tag_to_task` Add tag to task |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_task_to_project` Add task to project |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_task_to_section` Move task to section |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_project` Create project |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_section` Create section | Asana/Create project | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_subtask` Create subtask | Asana/Create subtask | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_tag` Create tag |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_task` Create task | Asana/Create task | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.MISSING_LOCATION | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_team` Create team | Asana/Create team | New: AA Asana teams. | New Create, Get and Update team actions. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_attachment` Delete attachment | Asana/Delete attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_comment` Delete comment | Asana/Delete comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_project` Delete project | Asana/Delete project | New: AA Asana Delete project. | New action, with a confirm safeguard. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_task` Delete task |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_attachment` Download attachment | Asana/Download attachment | New: AA Asana Download and Delete attachment. | New actions; links to Google Drive or Box are reported clearly instead of downloading a web page. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `duplicate_task` Duplicate task | Asana/Duplicate task | New: AA Asana Duplicate task. | New action; choose what is copied along. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_project` Get project |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_task` Get task |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_team` Get team | Asana/Get team | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user` Get user |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /users/me -> finished | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_comments` List comments | Asana/Get comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_projects` List projects |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_sections` List sections | Asana/Get project; Asana/List projects | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_subtasks` List subtasks |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_tags` List tags |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_tasks` List tasks | Asana/List task attachments; Asana/List tasks | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.MISSING_FILTER | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_users` List users | Asana/Get teams for user; Asana/Get user; Asana/List users | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_workspaces` List workspaces | Asana/Get teams in workspace | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /workspaces -> finished | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_followers` Remove followers |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_tag_from_task` Remove tag from task | Asana/Delete task | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search_tasks` Search tasks | Asana/Get task | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_comment` Update comment | Asana/Update comment | New: AA Asana Update and Delete comment. | New actions. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_project` Update project | Asana/Update project | New: AA Asana Update project. | New action (name, notes, owner, due date, archived, other fields). | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_task` Update task | Asana/Update task | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_team` Update team | Asana/Update team | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_attachment` Upload attachment | Asana/Upload attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ASANA.BAD_ID | CommandSamplesTest (cli, 15), AsanaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Azure DevOps (`ooa.azure_devops`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_pull_request_comment` Comment on pull request |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/git/repositories/x/pullRequests/1/threads -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_test_cases_to_suite` Add test cases to suite | Azure DevOps/Add test cases to suite | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.BAD_ID | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_work_item_comment` Add work item comment |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/wit/workItems/1/comments -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `attach_file_to_work_item` Attach file to work item |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/wit/attachments -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `complete_pull_request` Complete pull request |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/git/repositories/x/pullrequests/1 -> AZURE_DEVOPS.NOT_ACTIVE | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_pipeline` Create pipeline | Azure DevOps/Create pipeline | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/pipelines -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_pull_request` Create pull request |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/git/repositories/x/pullrequests -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_test_case` Create test case | Azure DevOps/Create test case | New: AA Azure DevOps test management (plans, suites, cases, points, runs) had no equivalent; ours covered work items, repositories and pipelines. | 19 new actions: list/get/create/edit/delete test plans, create/edit/delete suites (static, requirement, query), add/remove test cases, create test case with steps, list/update test points, create/get test runs, get/create/edit/delete pipelines. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_test_plan` Create test plan | Azure DevOps/Create test plan | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_test_run` Create test run | Azure DevOps/Create test run | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/test/runs -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_test_suite` Create test suite | Azure DevOps/Create test suite | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (in the create_test_plan sample) | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_work_item` Create work item |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/wit/workitems/$x -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_pipeline` Delete pipeline | Azure DevOps/Delete pipeline | Same options as documented in property-gaps; nothing further found. | New, with a confirm safeguard. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.CONFIRM_REQUIRED | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_test_plan` Delete test plan | Azure DevOps/Delete test plan | Same options as documented in property-gaps; nothing further found. | New, with a confirm safeguard. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.CONFIRM_REQUIRED | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_test_suite` Delete test suite | Azure DevOps/Delete test suite | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /x/_apis/testplan/Plans/1/suites/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_work_item` Delete work item | Azure DevOps/Delete test case | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: DELETE /_apis/wit/workitems/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_build` Get build |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/build/builds/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_pipeline` Get pipeline details | Azure DevOps/Get pipeline details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/pipelines/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_pipeline_run` Get pipeline run | Azure DevOps/Get pipeline run details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/pipelines/1/runs/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_project` Get project |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /_apis/projects/x -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_pull_request` Get pull request |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/git/repositories/x/pullrequests/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_test_plan` Get test plan details | Azure DevOps/Get test plan details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/testplan/plans/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_test_run` Get test run details | Azure DevOps/Get test run details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/test/runs/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_work_item` Get work item |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /_apis/wit/workitems/1 -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_builds` List builds |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/build/builds -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_iteration_work_items` List sprint work items |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/work/teamsettings/iterations/x/workitems -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_iterations` List sprints (iterations) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/work/teamsettings/iterations -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_pipelines` List pipelines | Azure DevOps/List pipelines | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/pipelines -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_projects` List projects |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /_apis/projects -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_pull_requests` List pull requests |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/git/repositories/x/pullrequests -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_repositories` List repositories |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/git/repositories -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_test_plans` List test plans | Azure DevOps/List test plans | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/testplan/plans -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_test_points` List test points | Azure DevOps/List test points | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/testplan/Plans/1/Suites/1/TestPoint -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_work_item_comments` List work item comments |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /x/_apis/wit/workItems/1/comments -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `query_work_items` Query work items (WIQL) |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/wit/wiql -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `queue_build` Queue build |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/build/builds -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_test_cases_from_suite` Remove test cases from suite | Azure DevOps/Remove test cases from suite | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.BAD_ID | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run_pipeline` Run pipeline | Azure DevOps/List pipeline runs; Azure DevOps/Run pipeline | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /x/_apis/pipelines/1/runs -> finished | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_pipeline` Edit pipeline | Azure DevOps/Edit pipeline | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_test_plan` Edit test plan | Azure DevOps/Edit test plan | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_test_point` Update test point | Azure DevOps/Update test point | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_test_suite` Edit test suite | Azure DevOps/Edit test suite | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_work_item` Update work item | Azure DevOps/Edit test case | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> AZURE_DEVOPS.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `pipeline_run_finished (condition)` Pipeline run finished |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | unit test | CommandSamplesTest (cli, 15), AzureDevOpsPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Box (`ooa.box`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_collaborator` Invite collaborator |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.NO_COLLABORATOR | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `copy_file` Copy file | Box/Copy file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_folder` Create folder | Box/Create folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /2.0/folders -> finished | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_shared_link` Create shared link | Box/Create shared link | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_file` Delete file | Box/Delete file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_folder` Delete folder | Box/Delete folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_file` Download file | Box/Download file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_file_info` Get file info |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_collaborators` List collaborators |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_folder` List folder items | Box/Get folder details; Box/List folder items; Box/Search file/folder | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /2.0/folders/0/items -> finished | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_file` Move file | Box/Move file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_collaborator` Remove collaborator |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_shared_link` Remove shared link |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_file` Rename file |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> BOX.BAD_ID | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search` Search |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /2.0/search -> finished | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_file` Upload file | Box/Upload file | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/2.0/files/content -> finished | BoxPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### DocuSign (`ooa.docusign`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_recipients` Add recipients | DocuSign/Add recipients | New: AA DocuSign Add recipients. | New action: signers and copy recipients with signing order. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_envelope` Create envelope from documents |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FILE.NOT_FOUND | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_envelope_from_template` Create envelope from template |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_RECIPIENT | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_signing_url` Get embedded signing link |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> FLOW.INVALID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_combined_pdf` Download combined PDF |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_document` Download document |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_DOCUMENT | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_zip` Download signed ZIP | DocuSign/Download signed ZIP; DocuSign/Download signed PDF | New: AA DocuSign Download signed ZIP. | New action. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_custom_fields` Get envelope custom fields |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_envelope_status` Get envelope status | DocuSign/Get envelope status | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_form_data` Get filled-in form data |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_template` Get template |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_documents` List envelope documents | DocuSign/Get document list | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_envelopes` List envelopes | DocuSign/Get envelope details; DocuSign/List envelopes | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v2.1/accounts/x/envelopes -> finished | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_recipients` List recipients | DocuSign/Get envelope recipients | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_templates` List templates | DocuSign/Get template; DocuSign/List templates | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /v2.1/accounts/x/templates -> finished | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `resend_envelope` Resend envelope | DocuSign/Resend envelope | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_envelope` Send draft envelope | DocuSign/Send envelope from files; DocuSign/Send envelope from template | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_custom_fields` Set envelope custom fields |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_recipient` Update recipient |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `void_envelope` Void envelope | DocuSign/Void envelope | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> DOCUSIGN.BAD_ID | CommandSamplesTest (cli, 15), DocuSignPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### GitHub (`ooa.github`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_issue_comment` Comment on issue or pull request | GitHub/Add Comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /repos/x/x/issues/1/comments -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `close_issue` Close issue |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PATCH /repos/x/x/issues/1 -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_branch` Create branch |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_issue` Create issue | GitHub/Create Issue | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_pull_request` Create pull request | GitHub/Create Pull Request | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /repos/x/x/pulls -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_release` Create release |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /repos/x/x/releases -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_file` Get file contents |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/contents/x -> GITHUB.NOT_A_FILE | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_issue` Get issue |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/issues/1 -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_latest_release` Get latest release |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/releases/latest -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_pull_request` Get pull request |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/pulls/1 -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_repository` Get repository | GitHub/Get Repository | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_workflow_run` Get workflow run |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/actions/runs/1 -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_branches` List branches | GitHub/List Branches | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/branches -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_issues` List issues | GitHub/Get Issue; GitHub/List Issues | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/issues -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_pull_requests` List pull requests | GitHub/Get Pull Request; GitHub/List Pull Requests | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/pulls -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_repositories` List repositories | GitHub/List Repositories | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /user/repos -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_workflow_runs` List workflow runs |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /repos/x/x/actions/runs -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `merge_pull_request` Merge pull request | GitHub/Merge pull Request | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: PUT /repos/x/x/pulls/1/merge -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `put_file` Create or update file |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GITHUB.NO_CONTENT | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run_workflow` Run workflow |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /repos/x/x/actions/workflows/x/dispatches -> finished | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_issue` Update issue | GitHub/Update issue | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> GITHUB.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `pull_request_is_merged (condition)` Pull request is merged |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | unit test | CommandSamplesTest (cli, 15), GitHubPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Okta (`ooa.okta`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `activate_user` Activate user | Okta/Activate user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x/lifecycle/activate -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_user_to_group` Add user to group | Okta/Add user to group | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users/x -> OKTA.GROUP_NOT_FOUND | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_user` Create user | Okta/Create user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `deactivate_user` Deactivate user | Okta/Deactivate user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x/lifecycle/deactivate -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_user` Delete user |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users/x -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user` Get user |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users/x -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_groups` List groups | Okta/Get group ID by name; Okta/Search groups | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/groups -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_user_groups` List user's groups | Okta/List user groups | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users/x/groups -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_users` List users | Okta/Search users; Okta/Get user details | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_user_from_group` Remove user from group | Okta/Remove user from group | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: GET /api/v1/users/x -> OKTA.GROUP_NOT_FOUND | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `reset_password` Reset password |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x/lifecycle/reset_password -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `suspend_user` Suspend user |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x/lifecycle/suspend -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `unsuspend_user` Unsuspend user |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x/lifecycle/unsuspend -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_user` Update user profile | Okta/Update user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: POST /api/v1/users/x -> finished | OktaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Zendesk (`ooa.zendesk`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_comment` Add comment | Zendesk/Add comment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `add_tags` Add tags |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_organization` Create organization | Zendesk/Create organization | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_ticket` Create ticket | Zendesk/Create ticket | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_user` Create user | Zendesk/Create user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_ticket` Delete ticket | Zendesk/Delete ticket | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_attachment` Download attachment | Zendesk/Download attachment; Zendesk/Download attachment via assign file | New: AA Zendesk Download attachment. | New action. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `find_users` Find users | Zendesk/List users | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_organization` Get organization |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_ticket` Get ticket |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user` Get user | Zendesk/Get user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_comments` List comments | Zendesk/Get ticket comments | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_groups` List groups |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_organizations` List organizations | Zendesk/Get organization; Zendesk/List organizations | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_ticket_fields` List ticket fields |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_tickets` List tickets |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_view_tickets` List tickets in view | Zendesk/List tickets | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_views` List views |  | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search_tickets` Search tickets | Zendesk/Get ticket | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_organization` Update organization | Zendesk/Update organization | New: AA Zendesk Update organization. | New action. | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | pass (1) | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_ticket` Update ticket | Zendesk/Update ticket | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_user` Update user | Zendesk/Update user | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NOTHING_TO_UPDATE | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_attachment` Upload attachment | Zendesk/Upload attachment | Same options as documented in property-gaps; nothing further found. |  | Proposal: named connections (address and vault credential kept once in Command Center, referenced by name) instead of repeating the token on every step; AA keeps a session per package. | mock server: no request (checks inputs or session first) -> ZENDESK.NO_EMAIL | CommandSamplesTest (cli, 15), ZendeskPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Custom Connectors (`ooa.connector`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `call_operation` Call operation |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> CONNECTOR.NOT_FOUND | ConnectorsTest, CustomConnectorTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `describe_operation` Describe operation |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> CONNECTOR.NOT_FOUND | ConnectorsTest, CustomConnectorTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `import_connector` Import connector |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> CONNECTOR.EMPTY_SPEC | ConnectorsTest, CustomConnectorTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_connectors` List connectors |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | ConnectorsTest, CustomConnectorTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_operations` List operations |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> CONNECTOR.NOT_FOUND | ConnectorsTest, CustomConnectorTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

## 11. Enterprise systems

### Active Directory (`ooa.active_directory`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `add_to_group` Add users to group | Active Directory/Add users to group | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `connect` Connect | Active Directory/Connect | Same as AA (address, bind user, credential, base DN); runs against an in-memory directory in the samples. |  |  | pass (2) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_computer` Create computer | Active Directory/Create computer | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_group` Create group | Active Directory/Create group | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_ou` Create organizational unit | Active Directory/Create organizational unit | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `create_user` Create user | Active Directory/Create user | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_computer` Delete computer | Active Directory/Delete computer | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_group` Delete group |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_ou` Delete organizational unit | Active Directory/Delete organizational unit | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_user` Delete user |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `disable_user` Disable user |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `disconnect` Disconnect | Active Directory/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `enable_user` Enable user |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_computer_property` Get computer properties | Active Directory/Get computer property | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_group_members` Get group members | Active Directory/Get all users of a group | Defect found by the directory sample: members were found only through memberOf, which some directories do not keep. | Falls back to the group's own member list when memberOf gives nothing. |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_group_property` Get group properties | Active Directory/Get group property | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_ou_property` Get organizational unit properties | Active Directory/Get organizational unit property | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_user` Get user |  | Defect found by the directory sample: users were looked up by objectCategory, which only Active Directory fills in, so Samba or OpenLDAP with an AD schema found nobody. | Lookups use objectClass user and not computer (same result on AD, works on other directories). |  | pass (1) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_computer` Move computer | Active Directory/Move computer | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_group` Move group |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_ou` Move organizational unit | Active Directory/Move organizational unit | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `move_user` Move user |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `remove_from_group` Remove users from group | Active Directory/Delete group; Active Directory/Delete user; Active Directory/Remove user from group | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_computer` Rename computer | Active Directory/Rename computer | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_group` Rename group | Active Directory/Rename group | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_ou` Rename organizational unit | Active Directory/Rename organizational unit | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `rename_user` Rename user | Active Directory/Rename user | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `reset_password` Reset password |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run_query` Run LDAP query | Active Directory/Run query | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search` Search |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_attribute` Set user property | Active Directory/Get user property; Active Directory/Set user property | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_computer_property` Set computer property | Active Directory/Set computer property | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_group_property` Set group property | Active Directory/Set group property | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_ou_property` Set organizational unit property | Active Directory/Set organizational unit property | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_password` Set password | Active Directory/Change user password | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `unlock_user` Unlock user |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_account_options` Update account options | Active Directory/Disable user account; Active Directory/Enable user account; Active Directory/Update account options | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `update_user_details` Update user details | Active Directory/Update user details | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the connect sample) | CommandSamplesTest (cli, 16-active-directory.json: in-memory LDAP directory), EnterpriseGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### SAP GUI (`ooa.sap`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `check_box` Set check box | SAP/Check/uncheck checkbox | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `click` Click | SAP/Left click | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `click_menu` Choose menu entry |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `close` Close connection | SAP/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `double_click` Double-click | SAP/Double click | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `expand_node` Expand tree node | SAP/Expand | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `export_table` Export table | SAP/Export table | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_children` Get children | SAP/Get children names; SAP/Get children text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_field` Get field | SAP/Get text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_item_count` Get item count | SAP/Get item count | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_selected_item` Get selected item | SAP/Get selected item | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_status` Get check box or radio state |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_status_bar` Get status bar | SAP/Get status | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_table_cell` Get table cell |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_table_cell_index` Find value in table | SAP/Get table cell index | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `log_on` Open system and log on |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `open_connection` Open system (single sign-on) | SAP/Connect | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `press` Press button |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `press_toolbar_button` Press grid or tree toolbar button |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `read_grid` Read grid |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `right_click` Right-click (context menu) | SAP/Click; SAP/Click menu; SAP/Right click | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run_transaction` Run transaction |  | SAP GUI scripting actions match AA's SAP package. AA's SAP BAPI package is not built: it needs the customer's licensed SAP Java Connector at run time. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `select_item` Select list item | SAP/Select item | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `select_radio` Select radio button | SAP/Select radio option | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_key` Send key | SAP/Send virtual key | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_field` Set field | SAP/Set text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_table_cell` Set table cell | SAP/Set table cell text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `table_cell_count` Get table cell count | SAP/Get cell count; SAP/Get table cell text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `table_column_count` Get table column count | SAP/Get column count | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `table_row_count` Get table row count | SAP/Get row count | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SAP.ERROR | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Terminal emulator (TN3270) (`ooa.terminal`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `clear_screen` Clear screen | Terminal Emulator/Clear terminal | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `connect` Connect | Terminal Emulator/Connect | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> TERMINAL.NOT_INSTALLED | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `disconnect` Disconnect | Terminal Emulator/Disconnect | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_all_fields` Get all fields | Terminal Emulator/Get all fields | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_cursor` Get cursor position |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_field` Get field |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_screen` Get screen text |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_text` Get text at position |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `hide_terminal` Hide terminal | Terminal Emulator/Hide terminal | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `search_field` Find text on screen | Terminal Emulator/Get field; Terminal Emulator/Get text; Terminal Emulator/Search field | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_key` Press key | Terminal Emulator/Send key | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `send_text` Type text | Terminal Emulator/Send text | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_cursor` Set cursor | Terminal Emulator/Set cursor position | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_field` Set field | Terminal Emulator/Set field | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `set_key_mapping` Set key mapping | Terminal Emulator/Set key mappings | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `show_terminal` Show terminal | Terminal Emulator/Show terminal | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `wait` Wait for screen |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `wait_for_text` Wait for text | Terminal Emulator/Wait | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | EnterpriseAiPropertiesTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Databricks (`ooa.databricks`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `cancel_run` Cancel job run | Databricks/Cancel job run | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DATABRICKS.BAD_ID | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `cancel_statement` Cancel statement | Databricks/Cancel SQL statement | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/2.0/sql/statements/x/cancel -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `delete_file` Delete file | Databricks/Delete file | New: AA Databricks Delete file had no equivalent. | New action for volumes, the workspace and DBFS (recursive folders on DBFS). |  | pass (1) | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `download_file` Download file | Databricks/Download file | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.0/dbfs/read -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `execute_sql` Run SQL statement | Databricks/Execute SQL statement | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/2.0/sql/statements -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `fetch_results` Get statement results | Databricks/Get SQL statement result | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.0/sql/statements/x -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_run` Get job run | Databricks/Get job run output; Databricks/Get job run status | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DATABRICKS.BAD_ID | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_statement_status` Get statement status |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.0/sql/statements/x -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_clusters` List clusters |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.0/clusters/list -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_jobs` List jobs | Databricks/List jobs | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.1/jobs/list -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run_job` Run job now | Databricks/Run job | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> DATABRICKS.BAD_ID | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `start_cluster` Start cluster |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /api/2.0/clusters/get -> STEP.TIMEOUT | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `terminate_cluster` Stop cluster |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/2.0/clusters/delete -> finished | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `upload_file` Upload file | Databricks/Upload file | Defect found by the connector smoke test: a reply without an upload handle crashed with a NullPointerException. | Fails with DATABRICKS.BAD_RESPONSE instead. |  | mock server: POST /api/2.0/dbfs/create -> DATABRICKS.BAD_RESPONSE | CommandSamplesTest (cli, 15), DatabricksPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Snowflake (`ooa.snowflake`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `cancel_statement` Cancel statement |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SNOWFLAKE.BAD_HANDLE | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `execute_statement` Run SQL statement | Snowflake/Bulk export; Snowflake/Bulk load; Snowflake/Delete rows; Snowflake/Execute DDL; Snowflake/Execute stored procedure; Snowflake/Select rows; Snowflake/Update rows | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `fetch_results` Get statement results |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SNOWFLAKE.BAD_HANDLE | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_statement_status` Get statement status |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SNOWFLAKE.BAD_HANDLE | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `insert_rows` Insert rows from data table | Snowflake/Insert Row | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_columns` List columns |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_databases` List databases |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_schemas` List schemas |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_tables` List tables |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_warehouses` List warehouses |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /api/v2/statements -> finished | SnowflakePackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Kafka (`ooa.kafka`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `create_topic` Create topic |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /v3/clusters -> KAFKA.NO_CLUSTER | KafkaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `get_topic` Get topic info |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /v3/clusters -> KAFKA.NO_CLUSTER | KafkaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `list_topics` List topics |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /v3/clusters -> KAFKA.NO_CLUSTER | KafkaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `produce_message` Send message | Kafka/Publish message | Same options as documented in property-gaps; nothing further found. |  |  | mock server: GET /v3/clusters -> KAFKA.NO_CLUSTER | KafkaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `read_messages` Read messages | Kafka/Commit offsets; Kafka/Consume message | Same options as documented in property-gaps; nothing further found. |  |  | mock server: POST /consumers/x -> finished | KafkaPackageTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

## 12. AI

### Generative AI (`ooa.genai`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `chat` Chat |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `classify` Classify |  | An answer outside the given labels fails with AI.LABEL_NOT_ALLOWED instead of passing through. |  |  | pass (2) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `detect_language` Detect language | AWS Comprehend NLP/Detect the language of the text | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `end_chat` End chat |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (in the chat sample) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `entities` Named entities | AWS Comprehend NLP/Named Entities | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `extract` Extract fields |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `generate_image` Generate picture |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> AI.NO_MODEL | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `key_phrases` Key phrases | AWS Comprehend NLP/Key Phrases | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `prompt` Prompt | Generative AI/Amazon Bedrock; Generative AI/Google Cloud; Generative AI/Microsoft Azure OpenAI; Generative AI/OpenAI; AI/AWS SageMaker; AI/Hugging Face | AA lists one package per provider (Bedrock, Vertex AI, Azure OpenAI, OpenAI, SageMaker, Hugging Face); ours has one set of actions with a provider choice and named model connections. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `prompt_with_images` Prompt with pictures |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> AI.NO_MODEL | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `sentiment` Sentiment | AWS Comprehend NLP/Get Sentiment | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `summarize` Summarise |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `translate` Translate |  | Same options as documented in property-gaps; nothing further found. |  |  | pass (1) | CommandSamplesTest (cli, 15: OpenAI-compatible mock model), AiProvidersTest, AiGapsTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### AI Agents (`ooa.ai_agent`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `run` Run AI agent |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SERVICE.UNAVAILABLE | AiAndWorkTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Prompt Skills (`ooa.ai_skills`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `list` List Prompt Skills |  | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | AiAndWorkTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run` Run Prompt Skill | AI Skill/Execute | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> AI.SKILL_NOT_FOUND | AiAndWorkTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

### Web goal agent (`ooa.web_agent`)

| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |
| --- | --- | --- | --- | --- | --- | --- |
| `end_session` End web agent session | UI Agent - Web/End Session | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> finished | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `run` Reach a goal | UI Agent - Web/Run | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> SESSION.NOT_FOUND | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |
| `start_session` Start web agent session | UI Agent - Web/Start Session | Same options as documented in property-gaps; nothing further found. |  |  | mock server: no request (checks inputs or session first) -> BROWSER.ERROR | WebAgentTest, ConnectorSmokeTest (cli: every command against a catch-all mock server) |

## Not yet compared

Listed for the next passes; the AA column is filled from the action names where known.

## AA actions and where they are in our command list

| AA action | Ours |
| --- | --- |
| Clipboard > Copy from | `ooa.desktop/clipboard_get` |
| Clipboard > Copy to | `ooa.desktop/clipboard_set` |
| Clipboard > Clear | `ooa.desktop/clipboard_clear` |
| String > Assign | `core/set` |
| String > Extract text | `ooa.string/extract_text` |
| String > Compare | `ooa.string/compare` |
| String > Evaluate variable | : not adopted: reading a variable whose name is held in text hides the data flow from validation and refactoring; use a Dictionary keyed by name (ooa.dictionary/get) instead |
| String > Find | `ooa.string/find` |
| String > Length | `ooa.string/length` |
| String > Lowercase | `ooa.string/change_case` |
| String > Generate random string | `ooa.string/random_string` |
| String > Replace | `ooa.string/replace` |
| String > Reverse | `ooa.string/reverse` |
| String > Split | `ooa.string/split` |
| String > Substring | `ooa.string/substring` |
| String > To boolean | `ooa.boolean/from_text` |
| String > To locale number | `ooa.string/to_number` |
| String > To number | `ooa.string/to_number` |
| String > Trim | `ooa.string/trim` |
| String > Uppercase | `ooa.string/change_case` |
| System > Lock computer | `ooa.system/lock` |
| System > Logoff | `ooa.system/log_off` |
| System > Restart | `ooa.system/restart` |
| System > Shutdown | `ooa.system/shut_down` |
| System > Get environment variable | `ooa.system/get_environment_variable` |
| Active Directory > Add users to group | `ooa.active_directory/add_to_group` |
| Active Directory > Change user password | `ooa.active_directory/set_password` |
| Active Directory > Connect | `ooa.active_directory/connect` |
| Active Directory > Create computer | `ooa.active_directory/create_computer` |
| Active Directory > Create group | `ooa.active_directory/create_group` |
| Active Directory > Create organizational unit | `ooa.active_directory/create_ou` |
| Active Directory > Create user | `ooa.active_directory/create_user` |
| Active Directory > Delete computer | `ooa.active_directory/delete_computer` |
| Active Directory > Delete group | `ooa.active_directory/remove_from_group` |
| Active Directory > Delete organizational unit | `ooa.active_directory/delete_ou` |
| Active Directory > Delete user | `ooa.active_directory/remove_from_group` |
| Active Directory > Disable user account | `ooa.active_directory/update_account_options` |
| Active Directory > Disconnect | `ooa.active_directory/disconnect` |
| Active Directory > Enable user account | `ooa.active_directory/update_account_options` |
| Active Directory > Get all users of a group | `ooa.active_directory/get_group_members` |
| Active Directory > Get computer property | `ooa.active_directory/get_computer_property` |
| Active Directory > Get group property | `ooa.active_directory/get_group_property` |
| Active Directory > Get organizational unit property | `ooa.active_directory/get_ou_property` |
| Active Directory > Get user property | `ooa.active_directory/set_attribute` |
| Active Directory > Move computer | `ooa.active_directory/move_computer` |
| Active Directory > Move organizational unit | `ooa.active_directory/move_ou` |
| Active Directory > Remove user from group | `ooa.active_directory/remove_from_group` |
| Active Directory > Rename computer | `ooa.active_directory/rename_computer` |
| Active Directory > Rename group | `ooa.active_directory/rename_group` |
| Active Directory > Rename organizational unit | `ooa.active_directory/rename_ou` |
| Active Directory > Rename user | `ooa.active_directory/rename_user` |
| Active Directory > Run query | `ooa.active_directory/run_query` |
| Active Directory > Set computer property | `ooa.active_directory/set_computer_property` |
| Active Directory > Set group property | `ooa.active_directory/set_group_property` |
| Active Directory > Set organizational unit property | `ooa.active_directory/set_ou_property` |
| Active Directory > Set user property | `ooa.active_directory/set_attribute` |
| Active Directory > Update account options | `ooa.active_directory/update_account_options` |
| Active Directory > Update user details | `ooa.active_directory/update_user_details` |
| AI > AWS SageMaker | `ooa.genai/prompt` |
| AI > Hugging Face | `ooa.genai/prompt` |
| AI Skill > Execute | `ooa.ai_skills/run` |
| App integration > Capture area | `ooa.ocr/read_screen` |
| App integration > Capture scrollable text | `ooa.desktop_ui/object_action` |
| App integration > Capture text of window | `ooa.desktop_ui/object_action` |
| Application > Open program/file | `ooa.application/start_program` |
| Asana > Assign file | : files are paths or variables; no separate assign step |
| Asana > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Asana > Create comment on task | `ooa.asana/add_comment` |
| Asana > Create project | `ooa.asana/create_section` |
| Asana > Create subtask | `ooa.asana/create_subtask` |
| Asana > Create task | `ooa.asana/create_task` |
| Asana > Create team | `ooa.asana/create_team` |
| Asana > Delete attachment | `ooa.asana/delete_attachment` |
| Asana > Delete comment | `ooa.asana/delete_comment` |
| Asana > Delete project | `ooa.asana/delete_project` |
| Asana > Delete task | `ooa.asana/remove_tag_from_task` |
| Asana > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Asana > Download attachment | `ooa.asana/download_attachment` |
| Asana > Duplicate task | `ooa.asana/duplicate_task` |
| Asana > Get comment | `ooa.asana/list_comments` |
| Asana > Get project | `ooa.asana/list_sections` |
| Asana > Get task | `ooa.asana/search_tasks` |
| Asana > Get team | `ooa.asana/get_team` |
| Asana > Get teams for user | `ooa.asana/list_users` |
| Asana > Get teams in workspace | `ooa.asana/list_workspaces` |
| Asana > Get user | `ooa.asana/list_users` |
| Asana > List projects | `ooa.asana/list_sections` |
| Asana > List task attachments | `ooa.asana/list_tasks` |
| Asana > List tasks | `ooa.asana/list_tasks` |
| Asana > List users | `ooa.asana/list_users` |
| Asana > Update comment | `ooa.asana/update_comment` |
| Asana > Update project | `ooa.asana/update_project` |
| Asana > Update task | `ooa.asana/update_task` |
| Asana > Update team | `ooa.asana/update_team` |
| Asana > Upload attachment | `ooa.asana/upload_attachment` |
| AWS Comprehend NLP > Key Phrases | `ooa.genai/key_phrases` |
| AWS Comprehend NLP > Detect the language of the text | `ooa.genai/detect_language` |
| AWS Comprehend NLP > Named Entities | `ooa.genai/entities` |
| AWS Comprehend NLP > Get Sentiment | `ooa.genai/sentiment` |
| Azure DevOps > Add test cases to suite | `ooa.azure_devops/add_test_cases_to_suite` |
| Azure DevOps > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Azure DevOps > Create pipeline | `ooa.azure_devops/create_pipeline` |
| Azure DevOps > Create test case | `ooa.azure_devops/create_test_case` |
| Azure DevOps > Create test plan | `ooa.azure_devops/create_test_plan` |
| Azure DevOps > Create test run | `ooa.azure_devops/create_test_run` |
| Azure DevOps > Create test suite | `ooa.azure_devops/create_test_suite` |
| Azure DevOps > Delete pipeline | `ooa.azure_devops/delete_pipeline` |
| Azure DevOps > Delete test case | `ooa.azure_devops/delete_work_item` |
| Azure DevOps > Delete test plan | `ooa.azure_devops/delete_test_plan` |
| Azure DevOps > Delete test suite | `ooa.azure_devops/delete_test_suite` |
| Azure DevOps > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Azure DevOps > Edit pipeline | `ooa.azure_devops/update_pipeline` |
| Azure DevOps > Edit test case | `ooa.azure_devops/update_work_item` |
| Azure DevOps > Edit test plan | `ooa.azure_devops/update_test_plan` |
| Azure DevOps > Edit test suite | `ooa.azure_devops/update_test_suite` |
| Azure DevOps > Get pipeline details | `ooa.azure_devops/get_pipeline` |
| Azure DevOps > Get pipeline run details | `ooa.azure_devops/get_pipeline_run` |
| Azure DevOps > Get test plan details | `ooa.azure_devops/get_test_plan` |
| Azure DevOps > Get test run details | `ooa.azure_devops/get_test_run` |
| Azure DevOps > List pipeline runs | `ooa.azure_devops/run_pipeline` |
| Azure DevOps > List pipelines | `ooa.azure_devops/list_pipelines` |
| Azure DevOps > List test plans | `ooa.azure_devops/list_test_plans` |
| Azure DevOps > List test points | `ooa.azure_devops/list_test_points` |
| Azure DevOps > Remove test cases from suite | `ooa.azure_devops/remove_test_cases_from_suite` |
| Azure DevOps > Run pipeline | `ooa.azure_devops/run_pipeline` |
| Azure DevOps > Update test point | `ooa.azure_devops/update_test_point` |
| Boolean > Assign | `core/set` |
| Boolean > Compare to | `ooa.boolean/compare` |
| Boolean > Equal to | `ooa.boolean/compare` |
| Boolean > Invert | `ooa.boolean/invert` |
| Boolean > To number | `ooa.boolean/to_number` |
| Boolean > To string | `ooa.boolean/to_text` |
| Box > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Box > Copy file | `ooa.box/copy_file` |
| Box > Create folder | `ooa.box/create_folder` |
| Box > Create shared link | `ooa.box/create_shared_link` |
| Box > Delete file | `ooa.box/delete_file` |
| Box > Delete folder | `ooa.box/delete_folder` |
| Box > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Box > Download file | `ooa.box/download_file` |
| Box > Get folder details | `ooa.box/list_folder` |
| Box > List folder items | `ooa.box/list_folder` |
| Box > Move file | `ooa.box/move_file` |
| Box > Search file/folder | `ooa.box/list_folder` |
| Box > Upload file | `ooa.box/upload_file` |
| Browser > Close | `ooa.browser/close` |
| Browser > Download file | `ooa.browser/download_url` |
| Browser > Get source code | `ooa.browser/get_page_source` |
| Browser > Run JavaScript | `ooa.browser/run_javascript` |
| Browser > Call a JavaScript function | `ooa.browser/call_javascript_function` |
| Browser > Find broken links | `ooa.browser/find_broken_links` |
| Browser > Go back | `ooa.browser/history` |
| Browser > Open | `ooa.browser/open` |
| Comment > Comment | `core/comment` |
| Credential > Assign | `core/set` |
| Credential Manager > Read Credential | `ooa.credential_manager/read_credential` |
| CSV/TXT > Close | : nothing to close: the file is read in one step |
| CSV/TXT > Open | `ooa.csv/read` |
| CSV/TXT > Read | `ooa.csv/read` |
| Currency Conversion > Historical Conversion | `ooa.currency/convert` |
| Currency Conversion > Real Time Conversion | `ooa.currency/convert` |
| Data Table > Assign | `core/set` |
| Data Table > Change column type | `ooa.datatable/change_column_type` |
| Data Table > Clear content | `ooa.datatable/clear` |
| Data Table > Get number of columns | `ooa.datatable/column_count` |
| Data Table > Delete column | `ooa.datatable/delete_column` |
| Data Table > Delete row | `ooa.datatable/delete_row` |
| Data Table > Get column name | `ooa.datatable/column_name` |
| Data Table > Insert column | `ooa.datatable/insert_column` |
| Data Table > Insert row | `ooa.datatable/insert_row` |
| Data Table > Join | `ooa.datatable/join` |
| Data Table > Merge | `ooa.datatable/merge` |
| Data Table > Remove duplicate rows | `ooa.datatable/remove_duplicates` |
| Data Table > Get number of rows | `ooa.datatable/row_count` |
| Data Table > Search for a value | `ooa.datatable/find` |
| Data Table > Set value of a single cell | `ooa.datatable/set_value` |
| Data Table > Sort | `ooa.datatable/sort` |
| Data Table > Write to file | `ooa.datatable/write_to_file` |
| Data Table > Write to file stream | : no stream type; write to a file (ooa.datatable/write_to_file) or keep the table in a variable |
| Database > Bulk insert | `ooa.database/bulk_insert` |
| Database > Begin database transaction | `ooa.database/begin_transaction` |
| Database > End database transaction | `ooa.database/commit` |
| Database > Connect | `ooa.database/connect` |
| Database > Disconnect | `ooa.database/disconnect` |
| Database > Read from | `ooa.database/query` |
| Database > Run stored procedure | `ooa.database/call_procedure` |
| Database > Export to data table | `ooa.database/query` |
| Database > Export to file stream | `ooa.database/export_to_file` |
| Database > Manage stored procedure | : create or drop procedures with Insert/update/delete (ooa.database/execute) and the database's own SQL |
| Database > Insert/Update/Delete | `ooa.database/execute` |
| Databricks > Cancel job run | `ooa.databricks/cancel_run` |
| Databricks > Cancel SQL statement | `ooa.databricks/cancel_statement` |
| Databricks > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Databricks > Delete file | `ooa.databricks/delete_file` |
| Databricks > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Databricks > Download file | `ooa.databricks/download_file` |
| Databricks > Execute SQL statement | `ooa.databricks/execute_sql` |
| Databricks > Get job run output | `ooa.databricks/get_run` |
| Databricks > Get job run status | `ooa.databricks/get_run` |
| Databricks > Get SQL statement result | `ooa.databricks/fetch_results` |
| Databricks > List jobs | `ooa.databricks/list_jobs` |
| Databricks > Run job | `ooa.databricks/run_job` |
| Databricks > Upload file | `ooa.databricks/upload_file` |
| Datetime > Add | `ooa.datetime/add` |
| Datetime > Assign | `ooa.datetime/parse` |
| Datetime > Difference between dates | `ooa.datetime/difference` |
| Datetime > Get | `ooa.datetime/get_part` |
| Datetime > Is after | `ooa.datetime/is_after` |
| Datetime > Is before | `ooa.datetime/is_before` |
| Datetime > Is equal | `ooa.datetime/is_equal` |
| Datetime > Is leap year | `ooa.datetime/is_leap_year` |
| Datetime > Subtract | `ooa.datetime/subtract` |
| Datetime > To string | `ooa.datetime/format` |
| Delay > Delay | `core/delay` |
| Dictionary > Assign | `core/set` |
| Dictionary > Get | `ooa.dictionary/get` |
| Dictionary > Put | `ooa.dictionary/put` |
| Dictionary > Remove | `ooa.dictionary/remove` |
| Dictionary > Size | `ooa.dictionary/size` |
| DLL > Close | `ooa.dll/close` |
| DLL > Open | `ooa.dll/open` |
| DLL > Run function | `ooa.dll/call` |
| DocuSign > Add recipients | `ooa.docusign/add_recipients` |
| DocuSign > Assign PDF file | : files are paths or variables; no separate assign step |
| DocuSign > Assign ZIP file | : files are paths or variables; no separate assign step |
| DocuSign > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| DocuSign > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| DocuSign > Download signed PDF | `ooa.docusign/download_zip` |
| DocuSign > Download signed ZIP | `ooa.docusign/download_zip` |
| DocuSign > Get document list | `ooa.docusign/list_documents` |
| DocuSign > Get envelope details | `ooa.docusign/list_envelopes` |
| DocuSign > Get envelope recipients | `ooa.docusign/list_recipients` |
| DocuSign > Get envelope status | `ooa.docusign/get_envelope_status` |
| DocuSign > Get template | `ooa.docusign/list_templates` |
| DocuSign > List envelopes | `ooa.docusign/list_envelopes` |
| DocuSign > List templates | `ooa.docusign/list_templates` |
| DocuSign > Resend envelope | `ooa.docusign/resend_envelope` |
| DocuSign > Send envelope from files | `ooa.docusign/send_envelope` |
| DocuSign > Send envelope from template | `ooa.docusign/send_envelope` |
| DocuSign > Void envelope | `ooa.docusign/void_envelope` |
| Email > Change status | `ooa.email/mark` |
| Email > Check if folder exists | `ooa.email/folder_exists` |
| Email > Delete all | `ooa.email/delete_all` |
| Email > Delete | `ooa.email/delete` |
| Email > Disconnect | `ooa.email/disconnect` |
| Email > Connect | `ooa.email/connect` |
| Email > Forward | `ooa.email/forward` |
| Email > Move all | `ooa.email/move_all` |
| Email > Move | `ooa.email/move` |
| Email > Reply All | `ooa.email/reply_all` |
| Email > Reply | `ooa.email/reply` |
| Email > Save all attachments | `ooa.email/save_all_attachments` |
| Email > Save attachments | `ooa.email/save_attachments` |
| Email > Save email | `ooa.email/save_email_as_eml` |
| Email > Send | `ooa.email/send` |
| Error handler > Try | `core/try` |
| Error handler > Catch | `core/try` |
| Error handler > Finally | `core/try` |
| Error handler > Throw | `core/throw` |
| Excel advanced > Access password protected worksheet | `ooa.excel/unprotect_sheet` |
| Excel advanced > Append workbook | `ooa.excel/append_workbook` |
| Excel advanced > Append worksheet | `ooa.excel/append_worksheet` |
| Excel advanced > Close | `ooa.excel/close` |
| Excel advanced > Convert excel to PDF | `ooa.excel_desktop/export_pdf` |
| Excel advanced > Create workbook | `ooa.excel/create_workbook` |
| Excel advanced > Create worksheet | `ooa.excel/add_sheet` |
| Excel advanced > Delete cells | `ooa.excel/delete_cells` |
| Excel advanced > Delete table column | `ooa.excel/delete_table_column` |
| Excel advanced > Break workbook links | `ooa.excel/break_links` |
| Excel advanced > Delete worksheet | `ooa.excel/delete_sheet` |
| Excel advanced > Disable or enable real-time screen update | : not needed: workbooks are edited as files with no visible Excel window, so there is no screen to update (ooa.excel_desktop drives Excel when a macro must run) |
| Excel advanced > Filter | `ooa.excel/filter` |
| Excel advanced > Find | `ooa.excel/find` |
| Excel advanced > Find next empty cell | `ooa.excel/find_next_empty_cell` |
| Excel advanced > Get cell color | `ooa.excel/get_cell_color` |
| Excel advanced > Get current worksheet name | `ooa.excel/get_current_sheet_name` |
| Excel advanced > Get multiple cells | `ooa.excel/rows` |
| Excel advanced > Get number of rows | `ooa.excel/row_count` |
| Excel advanced > Get sensitivity label | `ooa.excel/get_sensitivity_label` |
| Excel advanced > Get single cell | `ooa.excel/get_cell` |
| Excel advanced > Get cell address | `ooa.excel/cell_address` |
| Excel advanced > Get column name | `ooa.excel/column_name` |
| Excel advanced > Get row number | `ooa.excel/row_number` |
| Excel advanced > Get table range | `ooa.excel/get_table_range` |
| Excel advanced > Get workbook links | `ooa.excel/get_links` |
| Excel advanced > Get worksheet as data table | `ooa.excel/get_all_cells` |
| Excel advanced > Get worksheet names | `ooa.excel/get_sheet_names` |
| Excel advanced > Go to cell | `ooa.excel/go_to_cell` |
| Excel advanced > Go to next empty cell | `ooa.excel/go_to_next_empty_cell` |
| Excel advanced > Hide rows/columns in selection | `ooa.excel/hide_rows` |
| Excel advanced > Hide worksheet | `ooa.excel/hide_sheet` |
| Excel advanced > Insert/Delete rows/columns | `ooa.excel/insert_rows` |
| Excel advanced > Insert table column | `ooa.excel/insert_table_column` |
| Excel advanced > Open | `ooa.excel/open` |
| Excel advanced > Protect workbook | `ooa.excel/protect_workbook` |
| Excel advanced > Password protect worksheet | `ooa.excel/protect_sheet` |
| Excel advanced > Read cell formula | `ooa.excel/read_formula` |
| Excel advanced > Read column | `ooa.excel/read_column` |
| Excel advanced > Read row | `ooa.excel/read_row` |
| Excel advanced > Remove blank rows | `ooa.excel/remove_blank_rows` |
| Excel advanced > Rename worksheet | `ooa.excel/rename_sheet` |
| Excel advanced > Replace | `ooa.excel/replace` |
| Excel advanced > Retrieve sheets count | `ooa.excel/sheet_count` |
| Excel advanced > Run macro | `ooa.excel_desktop/run_macro` |
| Excel advanced > Save as | `ooa.excel/save_as` |
| Excel advanced > Save workbook | `ooa.excel/save` |
| Excel advanced > Select cells/rows/columns | `ooa.excel/select_range` |
| Excel advanced > Set cell | `ooa.excel/set_cell` |
| Excel advanced > Set cell formula | `ooa.excel/set_formula` |
| Excel advanced > Set sensitivity label | `ooa.excel/set_sensitivity_label` |
| Excel advanced > Sort | `ooa.excel/sort_range` |
| Excel advanced > Switch to sheet | `ooa.excel/switch_sheet` |
| Excel advanced > Unhide all worksheets | `ooa.excel/unhide_all_sheets` |
| Excel advanced > Unhide rows/columns in selection | `ooa.excel/unhide_rows` |
| Excel advanced > Unhide worksheet | `ooa.excel/unhide_sheet` |
| Excel advanced > Unprotect workbook | `ooa.excel/unprotect_workbook` |
| Excel advanced > Update workbook links | `ooa.excel/change_link` |
| Excel advanced > Write from data table | `ooa.excel/write_table` |
| Excel basic > Close | `ooa.excel/close` |
| Excel basic > Delete cells | `ooa.excel/delete_cells` |
| Excel basic > Find | `ooa.excel/find` |
| Excel basic > Get multiple cells | `ooa.excel/get_range` |
| Excel basic > Get single cell | `ooa.excel/get_cell` |
| Excel basic > Get cell address | `ooa.excel/cell_address` |
| Excel basic > Get column name | `ooa.excel/column_name` |
| Excel basic > Get row number | `ooa.excel/row_number` |
| Excel basic > Go to cell | `ooa.excel/go_to_cell` |
| Excel basic > Open | `ooa.excel/open` |
| Excel basic > Replace | `ooa.excel/replace` |
| Excel basic > Save workbook | `ooa.excel/save` |
| Excel basic > Set cell | `ooa.excel/set_cell` |
| Excel basic > Switch to sheet | `ooa.excel/switch_sheet` |
| File > Assign | `core/set` |
| File > Copy Desktop file | `ooa.file/copy` |
| File > Create | `ooa.file/write_text` |
| File > Delete | `ooa.file/delete` |
| File > Copy Control Room file | : repository files listed in the agent's 'files' are placed in the runner's workspace before the run; then use ooa.file/copy |
| File > Get name | `ooa.file/get_name` |
| File > Get path | `ooa.file/get_parent` |
| File > Open | `ooa.application/open_file` |
| File > Print | `ooa.file/print_files` |
| File > Print multiple files | `ooa.file/print_files` |
| File > Rename | `ooa.file/move` |
| File > Create shortcut | `ooa.file/create_shortcut` |
| File > Create symbolic link | `ooa.file/create_symbolic_link` |
| Folder > Zip | `ooa.file/zip` |
| Folder > Copy | `ooa.folder/copy` |
| Folder > Create | `ooa.folder/create` |
| Folder > Unzip | `ooa.file/unzip` |
| Folder > Delete | `ooa.folder/delete` |
| Folder > Create shortcut | `ooa.folder/create_shortcut` |
| Folder > Open | `ooa.folder/open_folder` |
| Folder > Rename | `ooa.folder/rename_folder` |
| Folder > Create symbolic link | `ooa.folder/create_symbolic_link` |
| Ftp/Sftp > Change folder | `ooa.file_transfer/change_folder` |
| Ftp/Sftp > Connect | `ooa.file_transfer/connect` |
| Ftp/Sftp > Create folder | `ooa.file_transfer/create_folder` |
| Ftp/Sftp > Delete folder | `ooa.file_transfer/delete_folder` |
| Ftp/Sftp > Delete files | `ooa.file_transfer/delete_files` |
| Ftp/Sftp > Disconnect | `ooa.file_transfer/disconnect` |
| Ftp/Sftp > Get folders | `ooa.file_transfer/download_folder` |
| Ftp/Sftp > Get files | `ooa.file_transfer/download` |
| Ftp/Sftp > Put folders | `ooa.file_transfer/upload_folder` |
| Ftp/Sftp > Put files | `ooa.file_transfer/upload` |
| Ftp/Sftp > Rename files | `ooa.file_transfer/rename` |
| Fuzzy Match > Fuzzy Match | `ooa.fuzzy/best_match` |
| Generative AI > Amazon Bedrock | `ooa.genai/prompt` |
| Generative AI > Google Cloud | `ooa.genai/prompt` |
| Generative AI > Microsoft Azure OpenAI | `ooa.genai/prompt` |
| Generative AI > OpenAI | `ooa.genai/prompt` |
| GitHub > Add Comment | `ooa.github/add_issue_comment` |
| GitHub > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| GitHub > Create Issue | `ooa.github/create_issue` |
| GitHub > Create Pull Request | `ooa.github/create_pull_request` |
| GitHub > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| GitHub > Get Issue | `ooa.github/list_issues` |
| GitHub > Get Pull Request | `ooa.github/list_pull_requests` |
| GitHub > Get Repository | `ooa.github/get_repository` |
| GitHub > List Branches | `ooa.github/list_branches` |
| GitHub > List Issues | `ooa.github/list_issues` |
| GitHub > List Pull Requests | `ooa.github/list_pull_requests` |
| GitHub > List Repositories | `ooa.github/list_repositories` |
| GitHub > Merge pull Request | `ooa.github/merge_pull_request` |
| GitHub > Update issue | `ooa.github/update_issue` |
| Gmail > Change status | `ooa.google/gmail_mark_message` |
| Gmail > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Gmail > Delete email | `ooa.google/gmail_delete_message` |
| Gmail > Delete all | `ooa.google/gmail_delete_all` |
| Gmail > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Gmail > Forward | `ooa.google/gmail_forward_message` |
| Gmail > Move email | `ooa.google/gmail_move_message` |
| Gmail > Move all | `ooa.google/gmail_move_all` |
| Gmail > Reply | `ooa.google/gmail_reply_message` |
| Gmail > Reply all | `ooa.google/gmail_reply_all` |
| Gmail > Save attachment | `ooa.google/gmail_save_attachments` |
| Gmail > Save all attachments | `ooa.google/gmail_save_all_attachments` |
| Gmail > Save email | `ooa.google/gmail_save_message_as_eml` |
| Gmail > Send | `ooa.google/gmail_send` |
| Google Calendar > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Calendar > Create event | `ooa.google/calendar_create_event` |
| Google Calendar > Delete event | `ooa.google/calendar_delete_event` |
| Google Calendar > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Drive > Assign File | `ooa.google/drive_rename_item` |
| Google Drive > Check Permissions | `ooa.google/drive_check_permission` |
| Google Drive > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Drive > Copy file | `ooa.google/drive_copy_file` |
| Google Drive > Copy folder | `ooa.google/drive_copy_folder` |
| Google Drive > Create file permission | `ooa.google/drive_create_permission` |
| Google Drive > Create Folder | `ooa.google/drive_create_folder` |
| Google Drive > Delete file | `ooa.google/drive_delete_item` |
| Google Drive > Delete file permission | `ooa.google/drive_delete_permission` |
| Google Drive > Delete Folder | `ooa.google/drive_delete_permission` |
| Google Drive > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Drive > Download file | `ooa.google/drive_download_export` |
| Google Drive > Find file/folder | `ooa.google/drive_find_items` |
| Google Drive > Get file information | `ooa.google/drive_list` |
| Google Drive > Get file permission | `ooa.google/drive_list_permissions` |
| Google Drive > Move file | `ooa.google/drive_move_item` |
| Google Drive > Move folder | `ooa.google/drive_move_item` |
| Google Drive > Rename file | `ooa.google/drive_rename_item` |
| Google Drive > Rename folder | `ooa.google/drive_rename_item` |
| Google Drive > Upload file | `ooa.google/drive_upload` |
| Google Sheets > Activate sheet | : no active sheet: each Sheets step names its sheet (stateless, so parallel steps cannot interfere) |
| Google Sheets > Autofit columns | `ooa.google/sheets_autofit_columns` |
| Google Sheets > Autofit rows | `ooa.google/sheets_autofit_rows` |
| Google Sheets > Close | : no sessions (each step names the spreadsheet) |
| Google Sheets > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Sheets > Create sheet | `ooa.google/sheets_insert_rows` |
| Google Sheets > Create workbook | `ooa.google/sheets_insert_rows` |
| Google Sheets > Delete cell | `ooa.google/sheets_delete_range` |
| Google Sheets > Delete range | `ooa.google/sheets_delete_range` |
| Google Sheets > Delete Row/Column | `ooa.google/sheets_delete_rows` |
| Google Sheets > Delete sheet | `ooa.google/sheets_delete_sheet` |
| Google Sheets > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Google Sheets > Format cells | `ooa.google/sheets_format_cells` |
| Google Sheets > Get All Sheet Names | `ooa.google/sheets_list_sheets` |
| Google Sheets > Get cell/text color | `ooa.google/sheets_get_cell_color` |
| Google Sheets > Get Current Sheet Name | : no current sheet (see Activate sheet); ooa.google/sheets_list_sheets lists them |
| Google Sheets > Get multiple cells | `ooa.google/sheets_read` |
| Google Sheets > Get number of rows | `ooa.google/sheets_row_count` |
| Google Sheets > Get single cell | `ooa.google/sheets_read` |
| Google Sheets > Go to cell | `ooa.google/sheets_set_cell` |
| Google Sheets > Hide worksheet | `ooa.google/sheets_hide_sheet` |
| Google Sheets > Insert cell | `ooa.google/sheets_insert_range` |
| Google Sheets > Insert range | `ooa.google/sheets_insert_range` |
| Google Sheets > Insert Row/Column | `ooa.google/sheets_insert_rows` |
| Google Sheets > Open spreadsheet | `ooa.google/sheets_create_spreadsheet` |
| Google Sheets > Paste cell | `ooa.google/sheets_set_cell` |
| Google Sheets > Read cell formula | `ooa.google/sheets_read_formula` |
| Google Sheets > Read column | `ooa.google/sheets_read_column` |
| Google Sheets > Read row | `ooa.google/sheets_read_row` |
| Google Sheets > Rename sheet | `ooa.google/sheets_rename_sheet` |
| Google Sheets > Retrieve sheets count | `ooa.google/sheets_row_count` |
| Google Sheets > Set cell/text color | `ooa.google/sheets_format_cells` |
| Google Sheets > Set cell formula | `ooa.google/sheets_set_formula` |
| Google Sheets > Set cell | `ooa.google/sheets_set_cell` |
| Google Sheets > Show worksheet | `ooa.google/sheets_show_sheet` |
| Google Sheets > Write from data table | `ooa.google/sheets_write_data_table` |
| If > If | `core/if` |
| If > Else If | `core/if` |
| If > Else | `core/if` |
| Image Recognition > Find image in window | `ooa.image/find_on_screen` |
| Image Recognition > Find window in window | `ooa.image/find_in_image` |
| Interactive forms > Display | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Close | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Show | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Hide | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Reset | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Validate form | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Change Form Title | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Assign | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Enable | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Disable | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Get | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Set | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Set focus | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Highlight | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Unhighlight | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Change label | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| Interactive forms > Dynamic area | : NOT BUILT (gap): needs a forms runtime that shows a *.form.json to the attended user and reacts to its events; proposed as its own task |
| JavaScript > Close | : no sessions: each step starts Node.js and ends it |
| JavaScript > Run JavaScript | `ooa.script/run_javascript` |
| JavaScript > Open | `ooa.script/run_javascript_function` |
| Jira > Add comment | `ooa.jira/add_comment` |
| Jira > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Jira > Create issue | `ooa.jira/create_issue` |
| Jira > Create project | `ooa.jira/create_project` |
| Jira > Delete comment | `ooa.jira/delete_comment` |
| Jira > Delete issue | `ooa.jira/delete_issue` |
| Jira > Delete project | `ooa.jira/delete_project` |
| Jira > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Jira > Get comments | `ooa.jira/get_comments` |
| Jira > Get issue | `ooa.jira/search` |
| Jira > Get projects | `ooa.jira/get_projects` |
| Jira > Update comment | `ooa.jira/update_comment` |
| Jira > Update issue | `ooa.jira/update_issue` |
| Jira > Update project | `ooa.jira/update_project` |
| JSON utilities > Insert node | `ooa.json/insert_node` |
| JSON utilities > Convert dictionary to JSON | `ooa.json/stringify` |
| JSON utilities > End session | : no sessions (see Start session) |
| JSON utilities > Get node array | `ooa.json/get_node_array` |
| JSON utilities > Get nodes | `ooa.json/get_nodes` |
| JSON utilities > Get node value | `ooa.json/get_node` |
| JSON utilities > Convert JSON to Dictionary | `ooa.json/parse` |
| JSON utilities > Convert JSON to Table | `ooa.json/to_table` |
| JSON utilities > Start session | : no sessions: JSON is an ordinary Dictionary/List variable (ooa.json/parse or read_file), which validates and shows in the variable watch |
| JSON utilities > Update node value | `ooa.json/update_node` |
| JSON utilities > Validate | `ooa.json/validate` |
| Kafka > Commit offsets | `ooa.kafka/read_messages` |
| Kafka > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Kafka > Consume message | `ooa.kafka/read_messages` |
| Kafka > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Kafka > Publish message | `ooa.kafka/produce_message` |
| List > Add item | `ooa.list/add_item` |
| List > Assign | `core/set` |
| List > Clear | `ooa.list/clear` |
| List > Get item | `ooa.list/get_item` |
| List > Join items | `ooa.list/join` |
| List > Append | `ooa.list/append_list` |
| List > Remove item | `ooa.list/remove_item` |
| List > Set item | `ooa.list/set_item` |
| List > Size | `ooa.list/size` |
| Logging > Log text to file | `ooa.log/write` |
| Logging > Log to Server | `ooa.log/log_to_command_center` |
| Logging > Log variables to file | `ooa.log/log_variables` |
| Loop > Loop | `core/loop` |
| Loop > Continue | `core/continue` |
| Loop > Break | `core/break` |
| Message box > Message box | `ooa.desktop/message_box` |
| Microsoft 365 Calendar > Add attachment | `ooa.microsoft365/add_event_attachment` |
| Microsoft 365 Calendar > Add attendee | `ooa.microsoft365/add_attendees` |
| Microsoft 365 Calendar > Cancel meeting | `ooa.microsoft365/cancel_event` |
| Microsoft 365 Calendar > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Calendar > Create meeting | `ooa.microsoft365/create_event` |
| Microsoft 365 Calendar > Delete attendee | `ooa.microsoft365/remove_attendees` |
| Microsoft 365 Calendar > Delete meeting information | `ooa.microsoft365/remove_attendees` |
| Microsoft 365 Calendar > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Calendar > Get available meeting slot(s) | `ooa.microsoft365/find_meeting_times` |
| Microsoft 365 Calendar > Modify meeting | `ooa.microsoft365/update_event` |
| Microsoft 365 Calendar > Respond to meeting | `ooa.microsoft365/respond_to_event` |
| Microsoft 365 Excel > Autofit columns | `ooa.microsoft365/excel_autofit_columns` |
| Microsoft 365 Excel > Autofit rows | `ooa.microsoft365/excel_autofit_rows` |
| Microsoft 365 Excel > Close | : no sessions (workbooks are addressed by item id or path on each step) |
| Microsoft 365 Excel > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Excel > Copy worksheet | `ooa.microsoft365/excel_show_worksheet` |
| Microsoft 365 Excel > Create table | `ooa.microsoft365/excel_create_table` |
| Microsoft 365 Excel > Create workbook | `ooa.microsoft365/excel_create_workbook` |
| Microsoft 365 Excel > Create sheet | `ooa.microsoft365/excel_add_worksheet` |
| Microsoft 365 Excel > Delete cell | `ooa.microsoft365/excel_delete_range` |
| Microsoft 365 Excel > Delete range | `ooa.microsoft365/excel_delete_range` |
| Microsoft 365 Excel > Delete row or column | `ooa.microsoft365/excel_delete_range` |
| Microsoft 365 Excel > Delete table | `ooa.microsoft365/excel_delete_table` |
| Microsoft 365 Excel > Delete worksheet | `ooa.microsoft365/excel_delete_worksheet` |
| Microsoft 365 Excel > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Excel > Find | `ooa.microsoft365/excel_find` |
| Microsoft 365 Excel > Find next empty cell | `ooa.microsoft365/excel_find_next_empty_cell` |
| Microsoft 365 Excel > Format cell | `ooa.microsoft365/excel_get_cell_format` |
| Microsoft 365 Excel > Get cell | `ooa.microsoft365/excel_used_range` |
| Microsoft 365 Excel > Get cell or text color | `ooa.microsoft365/excel_get_cell_format` |
| Microsoft 365 Excel > Get current worksheet name | : no current sheet: each step names its sheet (stateless) |
| Microsoft 365 Excel > Get multiple cells | `ooa.microsoft365/excel_used_range` |
| Microsoft 365 Excel > Get row count | `ooa.microsoft365/excel_read_row` |
| Microsoft 365 Excel > Get cell address | `ooa.microsoft365/excel_used_range` |
| Microsoft 365 Excel > Get column name | `ooa.microsoft365/excel_read_column` |
| Microsoft 365 Excel > Get row number | `ooa.microsoft365/excel_read_row` |
| Microsoft 365 Excel > Get table column | `ooa.microsoft365/excel_read_column` |
| Microsoft 365 Excel > Get table names | `ooa.microsoft365/excel_list_tables` |
| Microsoft 365 Excel > Get table row | `ooa.microsoft365/excel_get_table_rows` |
| Microsoft 365 Excel > Get table row count | `ooa.microsoft365/excel_get_table_rows` |
| Microsoft 365 Excel > Get worksheet as data table | `ooa.microsoft365/excel_read_range` |
| Microsoft 365 Excel > Get worksheet names | `ooa.microsoft365/excel_get_worksheets` |
| Microsoft 365 Excel > Go to cell | `ooa.microsoft365/excel_write_range` |
| Microsoft 365 Excel > Go to next empty cell | `ooa.microsoft365/excel_find_next_empty_cell` |
| Microsoft 365 Excel > Hide worksheet | `ooa.microsoft365/excel_hide_worksheet` |
| Microsoft 365 Excel > Insert cell | `ooa.microsoft365/excel_insert_range` |
| Microsoft 365 Excel > Insert range | `ooa.microsoft365/excel_insert_range` |
| Microsoft 365 Excel > Insert row or column | `ooa.microsoft365/excel_insert_range` |
| Microsoft 365 Excel > Open | : no sessions (each step names the workbook) |
| Microsoft 365 Excel > Paste cell | `ooa.microsoft365/excel_write_range` |
| Microsoft 365 Excel > Read cell format | `ooa.microsoft365/excel_get_cell_format` |
| Microsoft 365 Excel > Read cell formula | `ooa.microsoft365/excel_read_range` |
| Microsoft 365 Excel > Read column | `ooa.microsoft365/excel_read_column` |
| Microsoft 365 Excel > Read row | `ooa.microsoft365/excel_read_row` |
| Microsoft 365 Excel > Rename table | `ooa.microsoft365/excel_rename_table` |
| Microsoft 365 Excel > Rename worksheet | `ooa.microsoft365/excel_rename_worksheet` |
| Microsoft 365 Excel > Retrieve sheet count | `ooa.microsoft365/excel_get_worksheets` |
| Microsoft 365 Excel > Set cell | `ooa.microsoft365/excel_set_range` |
| Microsoft 365 Excel > Set cell color | `ooa.microsoft365/excel_set_range` |
| Microsoft 365 Excel > Set cell formula | `ooa.microsoft365/excel_set_range` |
| Microsoft 365 Excel > Show worksheet | `ooa.microsoft365/excel_show_worksheet` |
| Microsoft 365 Excel > Switch to sheet | `ooa.microsoft365/excel_show_worksheet` |
| Microsoft 365 Excel > Write from data table | `ooa.microsoft365/excel_write_data_table` |
| Microsoft 365 OneDrive > Assign file | `ooa.microsoft365/upload_file` |
| Microsoft 365 OneDrive > Check permission | `ooa.microsoft365/check_permissions` |
| Microsoft 365 OneDrive > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 OneDrive > Copy file or folder | `ooa.microsoft365/copy_item` |
| Microsoft 365 OneDrive > Create folder | `ooa.microsoft365/create_folder` |
| Microsoft 365 OneDrive > Delete file or folder | `ooa.microsoft365/delete_item` |
| Microsoft 365 OneDrive > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 OneDrive > Download file | `ooa.microsoft365/download_file` |
| Microsoft 365 OneDrive > Export to PDF | `ooa.microsoft365/export_to_pdf` |
| Microsoft 365 OneDrive > Find files and folders | `ooa.microsoft365/share_item` |
| Microsoft 365 OneDrive > Get file or folder information | `ooa.microsoft365/get_item_info` |
| Microsoft 365 OneDrive > Move file or folder | `ooa.microsoft365/move_item` |
| Microsoft 365 OneDrive > Rename file or folder | `ooa.microsoft365/rename_item` |
| Microsoft 365 OneDrive > Restore last version | `ooa.microsoft365/restore_version` |
| Microsoft 365 OneDrive > Upload file | `ooa.microsoft365/upload_file` |
| Microsoft 365 Outlook > Assign attachment(s) | `ooa.microsoft365/save_mail_attachments` |
| Microsoft 365 Outlook > Change status | `ooa.microsoft365/mark_mail` |
| Microsoft 365 Outlook > Check if folder exists | `ooa.microsoft365/mail_folder_exists` |
| Microsoft 365 Outlook > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Outlook > Delete | `ooa.microsoft365/delete_mail` |
| Microsoft 365 Outlook > Delete all | `ooa.microsoft365/delete_all_mail` |
| Microsoft 365 Outlook > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft 365 Outlook > Forward | `ooa.microsoft365/forward_mail` |
| Microsoft 365 Outlook > Move | `ooa.microsoft365/move_mail` |
| Microsoft 365 Outlook > Move all | `ooa.microsoft365/move_all_mail` |
| Microsoft 365 Outlook > Reply | `ooa.microsoft365/reply_mail` |
| Microsoft 365 Outlook > Reply All | `ooa.microsoft365/reply_all_mail` |
| Microsoft 365 Outlook > Save all attachments | `ooa.microsoft365/save_all_mail_attachments` |
| Microsoft 365 Outlook > Save attachments | `ooa.microsoft365/save_mail_attachments` |
| Microsoft 365 Outlook > Save email | `ooa.microsoft365/save_mail_attachments` |
| Microsoft 365 Outlook > Send | `ooa.microsoft365/send_mail` |
| Microsoft Teams > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft Teams > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Microsoft Teams > Get channel by name | `ooa.microsoft365/get_channel_by_name` |
| Microsoft Teams > Get channel details | `ooa.microsoft365/list_channels` |
| Microsoft Teams > Get team by name | `ooa.microsoft365/get_team_by_name` |
| Microsoft Teams > Get team details | `ooa.microsoft365/list_teams` |
| Microsoft Teams > Get user details | `ooa.microsoft365/get_user` |
| Microsoft Teams > Send channel message | `ooa.microsoft365/teams_message` |
| Microsoft Teams > Send chat message | `ooa.microsoft365/send_chat_message` |
| Mouse > Click | `ooa.desktop/mouse_click` |
| Mouse > Move | `ooa.desktop/mouse_move` |
| Mouse > Scroll | `ooa.desktop/mouse_scroll` |
| Number > Assign | `core/set` |
| Number > Decrement | `ooa.number/decrement` |
| Number > Increment | `ooa.number/increment` |
| Number > Random | `ooa.number/random` |
| Number > To string | `ooa.number/to_text` |
| OCR > Capture text by path | `ooa.ocr/read_image` |
| OCR > Capture text by URL | `ooa.ocr/read_url` |
| OCR > Capture window | `ooa.ocr/read_screen` |
| OCR > Capture area | `ooa.ocr/read_screen` |
| Okta > Activate user | `ooa.okta/activate_user` |
| Okta > Add user to group | `ooa.okta/add_user_to_group` |
| Okta > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Okta > Create user | `ooa.okta/create_user` |
| Okta > Deactivate user | `ooa.okta/deactivate_user` |
| Okta > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Okta > Get group ID by name | `ooa.okta/list_groups` |
| Okta > Get user details | `ooa.okta/list_users` |
| Okta > List user groups | `ooa.okta/list_user_groups` |
| Okta > Remove user from group | `ooa.okta/remove_user_from_group` |
| Okta > Search groups | `ooa.okta/list_groups` |
| Okta > Search users | `ooa.okta/list_users` |
| Okta > Update user | `ooa.okta/update_user` |
| PDF > Decrypt document | `ooa.pdf/decrypt` |
| PDF > Encrypt document | `ooa.pdf/encrypt` |
| PDF > Extract field | `ooa.pdf/read_form` |
| PDF > Extract image | `ooa.pdf/extract_images` |
| PDF > Extract text | `ooa.pdf/extract_text` |
| PDF > Get property | `ooa.pdf/get_properties` |
| PDF > Merge documents | `ooa.pdf/merge` |
| PDF > Split document | `ooa.pdf/split` |
| PGP > Create keys | `ooa.pgp/create_keys` |
| PGP > Decrypt files | `ooa.pgp/decrypt` |
| PGP > Encrypt files | `ooa.pgp/encrypt` |
| Play Sound > Play beep | `ooa.sound/beep` |
| Play Sound > Play media file | `ooa.sound/play` |
| PowerPoint > Add slide | `ooa.powerpoint/add_slide` |
| PowerPoint > Close | `ooa.powerpoint/close` |
| PowerPoint > Copy paste slide | `ooa.powerpoint/copy_slide` |
| PowerPoint > Create presentation | `ooa.powerpoint/create_presentation` |
| PowerPoint > Delete slide | `ooa.powerpoint/delete_slide` |
| PowerPoint > Find and replace picture | `ooa.powerpoint/find_replace_picture` |
| PowerPoint > Find and replace text | `ooa.powerpoint/find_replace_text` |
| PowerPoint > Format text | `ooa.powerpoint/format_text` |
| PowerPoint > Insert picture | `ooa.powerpoint/insert_picture` |
| PowerPoint > Insert table | `ooa.powerpoint/insert_table` |
| PowerPoint > Insert text | `ooa.powerpoint/insert_text` |
| PowerPoint > Open | `ooa.powerpoint/open` |
| PowerPoint > Save | `ooa.powerpoint/save` |
| PowerPoint > Save as | `ooa.powerpoint/save_as` |
| Printer > Get default | `ooa.printer/get_default` |
| Printer > Remove | `ooa.printer/remove_printer` |
| Printer > Set default | `ooa.printer/set_default` |
| Prompt > For file | `ooa.desktop/prompt_file` |
| Prompt > For folder | `ooa.desktop/prompt_folder` |
| Prompt > For value | `ooa.desktop/prompt_value` |
| Prompt > For yes/no | `ooa.desktop/prompt_yes_no` |
| Python script > Close | : no sessions: each step starts Python and ends it |
| Python script > Execute function | `ooa.script/run_python_function` |
| Python script > Execute script | `ooa.script/run_python` |
| Python script > Open | `ooa.script/run_python_function` |
| Record > Add column | `ooa.dictionary/put` |
| Record > Assign | `core/set` |
| Record > Delete column | `ooa.dictionary/remove` |
| Record > Update column | `ooa.dictionary/put` |
| Recorder > Capture | `ooa.browser/element_action` |
| Recorder > Structured data extraction | `ooa.browser/extract_table` |
| REST Web Services > Delete method | `ooa.rest/request` |
| REST Web Services > Get method | `ooa.rest/request` |
| REST Web Services > Get file stream | `ooa.rest/download_file` |
| REST Web Services > Patch method | `ooa.rest/request` |
| REST Web Services > Post method | `ooa.rest/request` |
| REST Web Services > Put method | `ooa.rest/request` |
| Salesforce > Assign file | : files are paths or variables; no separate assign step |
| Salesforce > Authentication | `ooa.salesforce/revoke_authentication` |
| Salesforce > Delete record | `ooa.salesforce/delete_record` |
| Salesforce > Get attachment(s) | `ooa.salesforce/get_attachments` |
| Salesforce > Execute SOQL | `ooa.salesforce/query` |
| Salesforce > Get record | `ooa.salesforce/query` |
| Salesforce > Insert record | `ooa.salesforce/create_record` |
| Salesforce > Revoke Authentication | `ooa.salesforce/revoke_authentication` |
| Salesforce > Update record | `ooa.salesforce/update_record` |
| Salesforce > Upload file attachment | `ooa.salesforce/upload_attachment` |
| Salesforce > Upsert record (using External ID) | `ooa.salesforce/upsert_by_external_id` |
| SAP > Check/uncheck checkbox | `ooa.sap/check_box` |
| SAP > Click | `ooa.sap/right_click` |
| SAP > Click menu | `ooa.sap/right_click` |
| SAP > Connect | `ooa.sap/open_connection` |
| SAP > Disconnect | `ooa.sap/close` |
| SAP > Double click | `ooa.sap/double_click` |
| SAP > Expand | `ooa.sap/expand_node` |
| SAP > Export table | `ooa.sap/export_table` |
| SAP > Get cell count | `ooa.sap/table_cell_count` |
| SAP > Get children names | `ooa.sap/get_children` |
| SAP > Get children text | `ooa.sap/get_children` |
| SAP > Get column count | `ooa.sap/table_column_count` |
| SAP > Get row count | `ooa.sap/table_row_count` |
| SAP > Get selected item | `ooa.sap/get_selected_item` |
| SAP > Get status | `ooa.sap/get_status_bar` |
| SAP > Get table cell index | `ooa.sap/get_table_cell_index` |
| SAP > Get table cell text | `ooa.sap/table_cell_count` |
| SAP > Get text | `ooa.sap/get_field` |
| SAP > Get item count | `ooa.sap/get_item_count` |
| SAP > Left click | `ooa.sap/click` |
| SAP > Right click | `ooa.sap/right_click` |
| SAP > Select item | `ooa.sap/select_item` |
| SAP > Select radio option | `ooa.sap/select_radio` |
| SAP > Send virtual key | `ooa.sap/send_key` |
| SAP > Set table cell text | `ooa.sap/set_table_cell` |
| SAP > Set text | `ooa.sap/set_field` |
| SAP BAPI > Create function | : not built: BAPI calls need the customer's licensed SAP Java Connector (JCo) at run time; planned as an add-on toolkit |
| SAP BAPI > Get field value | : not built (needs SAP JCo) |
| SAP BAPI > Get structure | : not built (needs SAP JCo, see Create function) |
| SAP BAPI > Get table | : not built (needs SAP JCo) |
| SAP BAPI > Run custom workflow | : not built (needs SAP JCo) |
| SAP BAPI > Run custom workflow advanced | : not built (needs SAP JCo) |
| SAP BAPI > Run function | : not built (needs SAP JCo) |
| SAP BAPI > Run standard workflow | : not built (needs SAP JCo) |
| SAP BAPI > Connect | : not built (needs SAP JCo) |
| SAP BAPI > Set field value | : not built (needs SAP JCo) |
| Screen > Capture area | `ooa.desktop/capture_screen` |
| Screen > Capture desktop | `ooa.desktop/capture_screen` |
| Screen > Capture window | `ooa.window/capture` |
| Service > Pause service | `ooa.services/pause` |
| Service > Resume service | `ooa.services/resume` |
| Service > Start service | `ooa.services/start` |
| Service > Get service status | `ooa.services/get_status` |
| Service > Stop service | `ooa.services/stop` |
| ServiceNow > Attachments | `ooa.servicenow/list_attachments` |
| ServiceNow > Authentication | `ooa.servicenow/revoke_authentication` |
| ServiceNow > Records | `ooa.servicenow/update_record` |
| SharePoint > Authentication | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| SharePoint > Folder & file | `ooa.microsoft365/share_item` |
| SharePoint > List & list item | `ooa.microsoft365/update_list_item` |
| Simulate keystrokes > Simulate keystrokes | `ooa.desktop/type_text` |
| Slack > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Slack > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Slack > Get channel ID by name | `ooa.slack/get_channel_id_by_name` |
| Slack > Get channel details | `ooa.slack/get_conversation_history` |
| Slack > Get conversation details | `ooa.slack/get_conversation_history` |
| Slack > Get user details | `ooa.slack/get_user_details` |
| Slack > Get workspace ID by name | `ooa.slack/get_workspace_id_by_name` |
| Slack > Get workspace details | `ooa.slack/get_workspace_id_by_name` |
| Slack > Send channel message | `ooa.slack/send_direct_message` |
| Slack > Send direct message | `ooa.slack/send_direct_message` |
| SNMP > Get | `ooa.network/snmp_get` |
| SNMP > Get next | `ooa.network/snmp_get_next` |
| SNMP > Send trap | `ooa.network/snmp_send_trap` |
| SNMP > Set | `ooa.network/snmp_set` |
| SNMP > Walk | `ooa.network/snmp_walk` |
| Snowflake > Bulk export | `ooa.snowflake/execute_statement` |
| Snowflake > Bulk load | `ooa.snowflake/execute_statement` |
| Snowflake > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Snowflake > Insert Row | `ooa.snowflake/insert_rows` |
| Snowflake > Delete rows | `ooa.snowflake/execute_statement` |
| Snowflake > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Snowflake > Execute DDL | `ooa.snowflake/execute_statement` |
| Snowflake > Execute stored procedure | `ooa.snowflake/execute_statement` |
| Snowflake > Select rows | `ooa.snowflake/execute_statement` |
| Snowflake > Update rows | `ooa.snowflake/execute_statement` |
| SOAP Web Service > SOAP Web Service | `ooa.soap/send` |
| Step > Step | `core/step` |
| Task Bot > Pause | `core/pause` |
| Task Bot > Run | `core/run_bot` |
| Task Bot > Stop | `core/stop` |
| Terminal Emulator > Clear terminal | `ooa.terminal/clear_screen` |
| Terminal Emulator > Connect | `ooa.terminal/connect` |
| Terminal Emulator > Disconnect | `ooa.terminal/disconnect` |
| Terminal Emulator > Get all fields | `ooa.terminal/get_all_fields` |
| Terminal Emulator > Get field | `ooa.terminal/search_field` |
| Terminal Emulator > Get text | `ooa.terminal/search_field` |
| Terminal Emulator > Hide terminal | `ooa.terminal/hide_terminal` |
| Terminal Emulator > Search field | `ooa.terminal/search_field` |
| Terminal Emulator > Send key | `ooa.terminal/send_key` |
| Terminal Emulator > Send text | `ooa.terminal/send_text` |
| Terminal Emulator > Set cursor position | `ooa.terminal/set_cursor` |
| Terminal Emulator > Set field | `ooa.terminal/set_field` |
| Terminal Emulator > Set key mappings | `ooa.terminal/set_key_mapping` |
| Terminal Emulator > Show terminal | `ooa.terminal/show_terminal` |
| Terminal Emulator > Wait | `ooa.terminal/wait_for_text` |
| Text file > Get text | `ooa.file/read_text` |
| Text file > Read variables | `ooa.file/read_variables` |
| Trigger loop > Trigger loop | : a Loop with the iterators ooa.triggerloop/file_arrives, process_starts or timer_tick (each event is one pass) |
| Trigger loop > Handle | : the Loop's children handle each event |
| Trigger loop > Break | `core/break` |
| UI Agent - Web > End Session | `ooa.web_agent/end_session` |
| UI Agent - Web > Run | `ooa.web_agent/run` |
| UI Agent - Web > Start Session | `ooa.web_agent/start_session` |
| VBScript > Close | : no sessions: each step starts the script host and ends it |
| VBScript > Run function | `ooa.script/run_vbscript_function` |
| VBScript > Open | `ooa.script/run_vbscript_function` |
| Wait > Wait for condition | `core/wait_until` |
| Wait > Wait for screen change | `ooa.image/wait_for_screen_change` |
| Wait > Wait for window | `ooa.window/wait_for` |
| Window > Activate | `ooa.window/activate` |
| Window > Get active window title | `ooa.window/get_active` |
| Window > Assign | : window variables are ordinary variables: core/set copies one; Capture and the window picker create them |
| Window > Close | `ooa.window/close` |
| Window > Close all | `ooa.window/close_all` |
| Window > Maximize | `ooa.window/maximize` |
| Window > Minimize | `ooa.window/minimize` |
| Window > Resize | `ooa.window/move_resize` |
| Window > Restore | `ooa.window/restore` |
| Window > Set title | `ooa.window/set_title` |
| Word > Close | `ooa.word/close` |
| Word > Create document | `ooa.word/create_document` |
| Word > Find and replace picture | `ooa.word/find_replace_picture` |
| Word > Find and replace text | `ooa.word/replace_text` |
| Word > Format text | `ooa.word/format_text` |
| Word > Insert picture | `ooa.word/insert_picture` |
| Word > Insert table | `ooa.word/add_table` |
| Word > Insert text | `ooa.word/insert_text` |
| Word > Open | `ooa.word/open` |
| Word > Read table | `ooa.word/read_tables` |
| Word > Read text | `ooa.word/read_text` |
| Word > Save | `ooa.word/save` |
| Word > Save as | `ooa.word/save_as` |
| Workday > Get Integration Event | `ooa.workday/get_integration_event` |
| Workday > Get Report | `ooa.workday/get_report` |
| Workday > Launch EIB | `ooa.workday/launch_integration` |
| Workday > Authentication | `ooa.workday/authenticate` |
| Workload > Insert work item | `ooa.workload/add_item` |
| Workload > Update work item | `ooa.workload/update_item` |
| XML > Delete node | `ooa.xml/delete_nodes` |
| XML > End session | : no sessions (see Start session) |
| XML > Execute XPath function | `ooa.xml/xpath_function` |
| XML > Get multiple nodes | `ooa.xml/get_nodes` |
| XML > Get single node | `ooa.xml/get_value` |
| XML > Insert node | `ooa.xml/insert_node` |
| XML > Save session data | `ooa.xml/save_file` |
| XML > Start session | : no sessions: actions take the XML text or a file and return the changed XML |
| XML > Update node | `ooa.xml/set_value` |
| XML > Validate XML document | `ooa.xml/validate` |
| Zendesk > Add comment | `ooa.zendesk/add_comment` |
| Zendesk > Connect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Zendesk > Create organization | `ooa.zendesk/create_organization` |
| Zendesk > Create ticket | `ooa.zendesk/create_ticket` |
| Zendesk > Create user | `ooa.zendesk/create_user` |
| Zendesk > Delete ticket | `ooa.zendesk/delete_ticket` |
| Zendesk > Disconnect | : no sessions: every step signs in with a token or credential from the vault, so there is nothing to connect or disconnect |
| Zendesk > Download attachment | `ooa.zendesk/download_attachment` |
| Zendesk > Download attachment via assign file | `ooa.zendesk/download_attachment` |
| Zendesk > Get organization | `ooa.zendesk/list_organizations` |
| Zendesk > Get ticket | `ooa.zendesk/search_tickets` |
| Zendesk > Get ticket comments | `ooa.zendesk/list_comments` |
| Zendesk > Get user | `ooa.zendesk/get_user` |
| Zendesk > List organizations | `ooa.zendesk/list_organizations` |
| Zendesk > List tickets | `ooa.zendesk/list_view_tickets` |
| Zendesk > List users | `ooa.zendesk/find_users` |
| Zendesk > Update organization | `ooa.zendesk/update_organization` |
| Zendesk > Update ticket | `ooa.zendesk/update_ticket` |
| Zendesk > Update user | `ooa.zendesk/update_user` |
| Zendesk > Upload attachment | `ooa.zendesk/upload_attachment` |

AA packages not mapped yet: Aisera, Analyze, AWS EC2, Bot Migration, Document Extraction, Google Document AI, ML - Microsoft Anomaly Detection, Process Composer.
