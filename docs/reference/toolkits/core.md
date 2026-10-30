# Core

Toolkit `core`, version 1.0.0. Control flow, variables and runtime built-ins.

[All toolkits](README.md)

## Contents

- **Logic**: Exit loop, Comment, Continue loop, If, Loop, Parallel, Step
- **Runtime**: Delay, Log message, Wait for condition
- **Agent**: Pause agent, Run Agent, Stop agent
- **Variables**: Assign variable
- **Error handling**: Throw error, Try

## Logic

### Exit loop

`core/break` (action)

Leave the innermost loop, or the enclosing loop with the given label.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Label | `label` | Text | No |  | Main | Label of the loop to leave; the innermost loop when empty. |

*Time limit 60 s.*

### Comment

`core/comment` (action)

Non-executing note.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | No |  | Main | Comment text. |

*Time limit 60 s.*

### Continue loop

`core/continue` (action)

Skip to the next pass of the innermost loop, or of the enclosing loop with the given label.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Label | `label` | Text | No |  | Main | Label of the loop to continue; the innermost loop when empty. |

*Time limit 60 s.*

### If

`core/if` (action)

Run the children when the condition is true; otherwise evaluate elseIf branches, then else.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Condition | `condition` | Any | Yes |  | Main | Condition object, expression string or $reference. |

*Time limit 60 s.*

### Loop

`core/loop` (action)

Repeat the children using an iterator: times, while, list, dictionary, dictionary_keys, dictionary_values, table_rows or a toolkit iterator (package/iterator).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Iterator | `iterator` | Text | Yes |  | Main | Iterator kind or package/iterator address. Example: `list` |
| Over | `over` | Any | No |  | Main | Collection for list, dictionary and table_rows iterators. Shown when: `{"iterator": ["list", "dictionary", "dictionary_keys", "dictionary_values", "table_rows"]}` |
| Count | `count` | Number | No |  | Main | Repetitions for the times iterator. Range: 0 to ; Shown when: `{"iterator": ["times"]}` |
| Condition | `condition` | Any | No |  | Main | Condition for the while iterator, checked before each pass. Shown when: `{"iterator": ["while"]}` |
| Iterator args | `iterator_args` | Dictionary | No |  | Main | Arguments for a toolkit iterator. Shown when: `{"iterator": ["!", "times", "while", "list", "dictionary", "dictionary_keys", "dictionary_values", "table_rows"]}` |
| As | `as` | Text | No |  | Main | Declared variable that receives the current item. |
| Index | `index` | Text | No |  | Main | Declared Number variable that receives the zero-based index. |
| Columns to | `columns_to` | Dictionary | No |  | Main | Column name to a declared variable: each pass puts that column of the current row (or key of the current record) in the variable. Shown when: `{"iterator": ["!", "times", "while"]}` |
| Maximum iterations | `max_iterations` | Number | No | `100000` | Main | Safety limit; the loop fails with LOOP.LIMIT when reached. Range: 1 to  |
| From index | `from_index` | Number | No |  | Main | First zero-based item or row to visit (list, dictionary, table and toolkit iterators); from the first when empty. Range: 0 to ; Shown when: `{"iterator": ["!", "times", "while"]}` |
| To index | `to_index` | Number | No |  | Main | Last zero-based item or row to visit, included; up to the last when empty. Range: 0 to ; Shown when: `{"iterator": ["!", "times", "while"]}` |
| Step | `step` | Number | No | `1` | Main | Visit every n-th item; for the times iterator, the amount the counter grows each pass. Shown when: `{"iterator": ["!", "while"]}` |
| Start | `start` | Number | No | `0` | Main | First counter value for the times iterator. Shown when: `{"iterator": ["times"]}` |
| Reverse | `reverse` | Yes/No | No | No | Main | Visit the items in reverse order (list, dictionary, table and toolkit iterators). Shown when: `{"iterator": ["!", "times", "while"]}` |
| Check at end | `check_at_end` | Yes/No | No | No | Main | While iterator: check the condition after each pass, so the children run at least once. Shown when: `{"iterator": ["while"]}` |
| Label | `label` | Text | No |  | Main | Name of this loop, so Exit loop and Continue loop inside nested loops can target it. |

*Time limit 60 s.*

### Parallel

`core/parallel` (action)

Run each child step as a concurrent branch and wait for all branches.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Maximum concurrency | `max_concurrency` | Number | No | `4` | Main | Maximum branches running at once. Range: 1 to 64 |

*Time limit 60 s.*

### Step

`core/step` (action)

Named group of steps for readability; no runtime effect.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Group title shown in Agent Studio. |

*Time limit 60 s.*

## Runtime

### Delay

`core/delay` (action)

Pause the run for a fixed time, or for a random time within a range.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Seconds | `seconds` | Number | Yes |  | Main | Time to wait (in the chosen unit); the low end of the range for a random delay. Range: 0 to  |
| Unit | `unit` | Text | No | `seconds` | Main | Unit of seconds and random_max. One of: `seconds`, `milliseconds`, `minutes` |
| Random, up to (same unit) | `random_max` | Number | No |  | Main | High end of a random delay; each run waits a random time between seconds and this value. Range: 0 to  |

*Time limit 60 s.*

### Log message

`core/log` (action)

Write a message to the run log.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | Yes |  | Main | Text to log; ${...} interpolation allowed. |
| Level | `level` | Text | No | `info` | Main | Log level. One of: `debug`, `info`, `warning`, `error` |

*Time limit 60 s.*

### Wait for condition

`core/wait_until` (action)

Poll a condition until it is true or the timeout expires (WAIT.TIMEOUT).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Condition | `condition` | Any | Yes |  | Main | Condition to poll. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait. Range: 0 to  |
| Poll (seconds) | `poll_seconds` | Number | No | `1` | Main | Interval between checks. Range: 0.05 to  |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | Main | Fail with WAIT.TIMEOUT when the time runs out; when off, carry on and set met to false. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `met` | Yes/No | True when the condition became true before the timeout. |

*Time limit 60 s.*

## Agent

### Pause agent

`core/pause` (action)

Pause the run until someone resumes it from Agent Studio or Command Center, or until the optional timeout.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | No |  | Main | Why the run is waiting; shown with the paused run. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | Main | Resume by itself after this many seconds; waits until resumed when empty. Range: 0 to  |

*Time limit 60 s.*

### Run Agent

`core/run_bot` (action)

Run a published child automation with mapped inputs and receive its outputs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Automation | `automation` | Text | Yes |  | Main | Id of the published automation. |
| Version | `version` | Any | No |  | Main | Version number or 'production' (default). |
| Inputs | `inputs` | Dictionary | No |  | Main | Values for the child's input variables. |
| Output map | `output_map` | Dictionary | No |  | Main | Child output name to the variable of this agent that receives it, e.g. {"total": "invoice_total"}. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | Main | Stop the child with CHILD.TIMEOUT when one run takes longer than this; no limit when empty. Range: 0 to  |
| Same session | `same_session` | Yes/No | No | No | Main | Share this agent's open sessions (workbooks, browsers, connections) with the child; sessions the child opens stay open for this agent. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | Main | Fail this step when the child fails; when off, set status to failed and the error output and carry on. |
| Repeat | `repeat` | Number | No | `1` | Main | Run the child this many times in a row. Range: 1 to  |
| Repeat delay (seconds) | `repeat_delay_seconds` | Number | No | `0` | Main | Wait between repeated runs. Range: 0 to ; Shown when: `{"repeat": ["!", "1"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `outputs` | Dictionary | The child's output variables (of the last run when repeated). |
| `status` | Text | Child run status: success or failed. |
| `error` | Dictionary | The child's error (code, message, stepId) when it failed and fail_on_error is off; empty otherwise. |
| `runs` | Number | How many times the child ran. |

*Time limit 60 s.*

### Stop agent

`core/stop` (action)

End the current agent at once; Finally blocks still run and the run ends with the chosen status.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | No |  | Main | Reason written to the run log. |
| Status | `status` | Text | No | `success` | Main | success ends the agent normally; failed ends it with error AGENT.STOPPED. One of: `success`, `failed` |

*Time limit 60 s.*

## Variables

### Assign variable

`core/set` (action)

Assign a value or expression result to a declared variable.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Variable | `variable` | Text | Yes |  | Main | Name of a declared variable. |
| Value | `value` | Any | No |  | Main | Value, $reference or {"expr": ...}. |

*Time limit 60 s.*

## Error handling

### Throw error

`core/throw` (action)

Raise an error with a custom code that Try/Catch can match.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Code | `code` | Text | Yes |  | Main | Error code, e.g. BUSINESS.INVOICE_DUPLICATE. |
| Message | `message` | Text | Yes |  | Main | Error message. |
| Suggested fix | `suggested_fix` | Text | No |  | Main | How to resolve the error. |
| Details | `details` | Dictionary | No |  | Main | Extra values carried with the error, readable in Catch. |
| Retryable | `retryable` | Yes/No | No | No | Main | Mark the error as temporary so retry policies try the step again. |

*Time limit 60 s.*

### Try

`core/try` (action)

Run the children; on error run the first matching catch block, then always run finally.

No settings.

*Time limit 60 s.*
