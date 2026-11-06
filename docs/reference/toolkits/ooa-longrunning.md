# Long-running agents

Toolkit `ooa.longrunning`, version 1.0.0. Suspend an agent until a time, a task, a work item, another run or a callback URL is called, and carry on from the same step; only from One CommandCenter.

[All toolkits](README.md)

## Contents

- **Long-running**: Create callback URL, Create task, Resume after delay, Suspend until, Wait for callback, Wait for work item, Wait for job, Wait for task

## Long-running

### Create callback URL

`ooa.longrunning/create_callback` (action)

Make a one-time web address that another system calls (HTTP POST with a JSON body) when its work is done. Give the address to that system, then use Wait for callback. The address is shown here only once and works once; One CommandCenter keeps only a fingerprint of it. Needs the Public address in Settings.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `url` | Text | The address to POST to: https://ONE-COMMANDCENTER/api/callbacks/TOKEN. Treat it as a secret: whoever has it can resume the run. |
| `callback_id` | Text | Give it to Wait for callback. |

*Time limit 60 s.*

### Create task

`ooa.longrunning/create_task` (action)

Put an approval or a form in the Task inbox for people to do, then use Wait for task to suspend until it is done. Assign it to user:NAME or role:NAME (several separated by commas).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Kind | `kind` | Text | No | `approval` | Main | approval: approve or reject, with a comment; form: fill in the form's fields. One of: `approval`, `form` |
| Title | `title` | Text | Yes |  | Main | What the task is, as the inbox lists it. Example: `Approve invoice 4711 (12,400 EUR)` |
| Assignee | `assignee` | Text | Yes |  | Main | Who may do it: user:NAME or role:NAME, several separated by commas. Example: `role:Finance approvers` |
| Description | `description` | Text | No |  | More options | More about the task. |
| Form | `form` | Dictionary | No |  | More options | For a form: {"fields": [{"id": "amount", "label": "Amount", "type": "number", "required": true}]}, as in workflow forms. Shown when: `{"kind": ["= 'form'"]}` |
| Data | `data` | Dictionary | No |  | More options | Details shown with the task. |
| Due (hours) | `due_hours` | Number | No |  | More options | Overdue after this many hours; never when empty. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `task_id` | Text | Give it to Wait for task. |

*Time limit 60 s.*

### Resume after delay

`ooa.longrunning/resume_after` (action)

Suspend the agent for a while, or until a date and time, without holding the runner; then carry on from this step. For short pauses use Delay instead.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Delay (seconds) | `delay_seconds` | Number | No |  | Main | How long to wait, in seconds. Range: 1 to 31536000 |
| Until | `until` | Text | No |  | Main | Or: when to carry on, a date and time (in the runner's time zone unless it has an offset). Example: `2026-11-09T08:00` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool; on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |

*Time limit 120 s.*

### Suspend until

`ooa.longrunning/suspend_until` (action)

Suspend the agent until something happens, then carry on from this step in a new run with the same values. The run shows as Suspended and its runner is free while it waits. Not inside a loop, a Parallel block, a Catch or a Finally; close browsers, workbooks and files first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Kind | `kind` | Text | Yes |  | Main | What to wait for: time, a Task inbox task, a work item, another run (job), or a callback URL being called. One of: `time`, `task`, `queue_item`, `job`, `callback` |
| Ref | `ref` | Text | Yes |  | Main | time: seconds from now or a date and time with its offset (2026-11-07T09:00:00+05:30); task: the task id; queue_item: the work item id; job: the run id; callback: the callback id. Example: `task_4b1d...` |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | More options | Stop waiting after this many seconds; wait as long as it takes when empty. Range: 1 to 31536000 |
| On timeout | `on_timeout` | Text | No | `error` | More options | error: the step fails with LONGRUN.TIMEOUT (catch it in a Try); continue: carry on with Timed out true. One of: `error`, `continue` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool (name or id); on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |
| `timed_out` | Yes/No | True when the timeout ended the wait (with On timeout set to continue). |
| `result` | Dictionary | What ended the wait: the callback's JSON body; a task's status, outcome, result and completed_by; a work item's status, result and error; a run's status, outputs and error. Empty for a time or a timeout. |

*Time limit 120 s.*

### Wait for callback

`ooa.longrunning/wait_for_callback` (action)

Suspend until the callback URL made by Create callback URL is called; its JSON body comes back as Result. A call that came before this step is not lost.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Callback ID | `callback_id` | Text | Yes |  | Main | From Create callback URL. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | More options | Stop waiting after this many seconds; wait as long as it takes when empty. Range: 1 to 31536000 |
| On timeout | `on_timeout` | Text | No | `error` | More options | error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true. One of: `error`, `continue` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool; on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |
| `timed_out` | Yes/No | True when the timeout ended the wait. |
| `result` | Dictionary | The JSON body the caller posted (a value that is not an object comes as {"value": ...}). |

*Time limit 120 s.*

### Wait for work item

`ooa.longrunning/wait_for_item` (action)

Suspend until a work item in a work queue ends as success or failed (for example one this agent added for another agent to process).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Item ID | `item_id` | Text | Yes |  | Main | The work item's id, from Add work item. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | More options | Stop waiting after this many seconds; wait as long as it takes when empty. Range: 1 to 31536000 |
| On timeout | `on_timeout` | Text | No | `error` | More options | error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true. One of: `error`, `continue` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool; on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |
| `timed_out` | Yes/No | True when the timeout ended the wait. |
| `result` | Dictionary | item_id, status (success or failed), result and error. |

*Time limit 120 s.*

### Wait for job

`ooa.longrunning/wait_for_job` (action)

Suspend until another run ends (success, failed or stopped). A run that is itself suspended is followed to the run that carries it on.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Job ID | `job_id` | Text | Yes |  | Main | The run's id. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | More options | Stop waiting after this many seconds; wait as long as it takes when empty. Range: 1 to 31536000 |
| On timeout | `on_timeout` | Text | No | `error` | More options | error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true. One of: `error`, `continue` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool; on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |
| `timed_out` | Yes/No | True when the timeout ended the wait. |
| `result` | Dictionary | job_id, status (success, failed or stopped), outputs and error. |

*Time limit 120 s.*

### Wait for task

`ooa.longrunning/wait_for_task` (action)

Suspend until a Task inbox task (an approval or a form, from Create task or a workflow) is completed or cancelled. The run's user must be allowed to see the task, or the task was made by this run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Task ID | `task_id` | Text | Yes |  | Main | From Create task. |
| Timeout (seconds) | `timeout_seconds` | Number | No |  | More options | Stop waiting after this many seconds; wait as long as it takes when empty. Range: 1 to 31536000 |
| On timeout | `on_timeout` | Text | No | `error` | More options | error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true. One of: `error`, `continue` |
| Pool | `pool` | Text | No |  | More options | Carry on on any runner of this pool; on the same runner when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `resumed_by` | Text | resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout). |
| `timed_out` | Yes/No | True when the timeout ended the wait. |
| `result` | Dictionary | task_id, status (completed or cancelled), outcome (approved, rejected, submitted), result (the answers, or approved/approver/comment) and completed_by. |

*Time limit 120 s.*
