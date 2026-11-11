# Core

Toolkit `core`, version 1.0.0. Control flow, variables and runtime built-ins.

[All toolkits](README.md)

## Contents

- **Logic**: Exit loop, Comment, Continue loop, Go to, If, Label, Loop, Parallel, State machine, Step, Switch
- **Runtime**: Delay, Log message, Wait for condition
- **Agent**: Exit subflow, Pause agent, Run Agent, Run subflow, Stop agent
- **Variables**: Assign variable, Assign several variables
- **Error handling**: Throw error, Try

## Logic

### Exit loop

`core/break` (action)

Leave the loop now and carry on after it; name a loop to leave an outer one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Label | `label` | Text | No |  | Main | Label of the loop to leave; the innermost loop when empty. |

*Time limit 60 s.*

### Comment

`core/comment` (action)

A note in the steps for people reading the agent; it does nothing when the agent runs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | No |  | Main | Comment text. |

*Time limit 60 s.*

### Continue loop

`core/continue` (action)

Skip the rest of this pass and start the next pass of the loop; name a loop to continue an outer one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Label | `label` | Text | No |  | Main | Label of the loop to continue; the innermost loop when empty. |

*Time limit 60 s.*

### Go to

`core/goto` (action)

Jump to a Label in the same list of steps or one around it. Kept for agents moved from other tools; a Loop, If or subflow is easier to follow.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Label | `label` | Text | Yes |  | Main | Name of the Label to carry on from. |

*Time limit 60 s.*

### If

`core/if` (action)

Run the steps inside when the condition is true; otherwise try each Else if, then Else.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Condition | `condition` | Any | Yes |  | Main | Condition object, expression string or $reference. |

*Time limit 60 s.*

### Label

`core/label` (action)

A named place in the steps that Go to can jump to; it does nothing itself.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the place, unique among the labels Go to steps can see. |

*Time limit 60 s.*

### Loop

`core/loop` (action)

Repeat the steps inside: a number of times, while a condition holds, or once for each item, row, file, e-mail and so on.

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

Run each step inside at the same time, and wait until all of them have finished.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Maximum concurrency | `max_concurrency` | Number | No | `4` | Main | Maximum branches running at once. Range: 1 to 64 |

*Time limit 60 s.*

### State machine

`core/state_machine` (action)

Move between named states: each state runs its steps, then the first transition whose condition holds takes the agent to the next state, until a final state.and moves to the next state; the step ends in a final state. Fails with STATE.NO_TRANSITION when a state that is not final has no true transition.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Initial | `initial` | Text | No |  | Main | Name of the state to start in; the first state when empty. |
| Maximum transitions | `max_transitions` | Number | No | `1000` | Main | Safety limit; the step fails with STATE.LIMIT when more transitions than this are taken. Range: 1 to  |

*Time limit 60 s.*

### Step

`core/step` (action)

A named group of steps that keeps the agent tidy; it changes nothing when the agent runs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Group title shown in One AgentStudio. |

*Time limit 60 s.*

### Switch

`core/switch` (action)

Compare one value with each case in turn and run the steps of the first case that matches; Default runs when none does.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main | Value to compare with each case's values: text, a number, a $reference or an expression. Shown when: `{"match": ["!", "expression"]}` |
| Match | `match` | Text | No | `equals` | Main | How a case value matches: equals (like ==, so 5 and "5" match), contains, starts_with, ends_with, regex (the case value is a pattern), or expression (each case value is a condition and the value is not used). One of: `equals`, `contains`, `starts_with`, `ends_with`, `regex`, `expression` |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Compare text without upper/lower case. Shown when: `{"match": ["!", "expression"]}` |

*Time limit 60 s.*

## Runtime

### Delay

`core/delay` (action)

Wait a fixed time, or a random time between two values, before the next step.

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

Wait until a condition is true, checking it again and again, up to a time limit.

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

### Exit subflow

`core/exit_subflow` (action)

Leave the subflow now; the agent carries on after the step that ran it (Finally steps still run).

No settings.

*Time limit 60 s.*

### Pause agent

`core/pause` (action)

Stop and wait until someone presses Resume in One AgentStudio or One CommandCenter, or until the time limit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | No |  | Main | Why the run is waiting; shown with the paused run. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | Main | Resume by itself after this many seconds; waits until resumed when empty. Range: 0 to  |

*Time limit 60 s.*

### Run Agent

`core/run_bot` (action)

Run another agent with the inputs you give it, and keep the outputs it gives back.

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

### Run subflow

`core/run_subflow` (action)

Run one of this agent's subflows (a named list of steps kept beside the main steps), then carry on; it shares the agent's variables.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Subflow | `subflow` | Text | Yes |  | Main | Name of the subflow to run. |

*Time limit 60 s.*

### Stop agent

`core/stop` (action)

End the agent now with the status you choose; Finally steps still run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | No |  | Main | Reason written to the run log. |
| Status | `status` | Text | No | `success` | Main | success ends the agent normally; failed ends it with error AGENT.STOPPED. One of: `success`, `failed` |

*Time limit 60 s.*

## Variables

### Assign variable

`core/set` (action)

Put a value, or the result of a calculation, in a variable.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Variable | `variable` | Text | Yes |  | Main | Name of a declared variable. |
| Value | `value` | Any | No |  | Main | Value, $reference or {"expr": ...}. |

*Time limit 60 s.*

### Assign several variables

`core/set_many` (action)

Put values in several variables in one step, in order: a later value can use a variable set above it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Assignments | `assignments` | List | Yes |  | Main | Records with variable (a declared variable's name) and value (a value, $reference or {"expr": ...}). Example: `[{"variable": "total", "value": 0}, {"variable": "status", "value": "new"}]` |

*Time limit 60 s.*

## Error handling

### Throw error

`core/throw` (action)

Raise an error with a code of your choice, for a Catch (or On error) to handle.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Code | `code` | Text | Yes |  | Main | Error code, e.g. BUSINESS.INVOICE_DUPLICATE. |
| Message | `message` | Text | No |  | Main | What went wrong, for the log and the Catch; the code when empty. |
| Suggested fix | `suggested_fix` | Text | No |  | Main | How to resolve the error. |
| Details | `details` | Dictionary | No |  | Main | Extra values carried with the error, readable in Catch. |
| Retryable | `retryable` | Yes/No | No | No | Main | Mark the error as temporary so retry policies try the step again. |

*Time limit 60 s.*

### Try

`core/try` (action)

Run the steps inside; if one fails, run the first Catch that takes the error, then always Finally.

No settings.

*Time limit 60 s.*
