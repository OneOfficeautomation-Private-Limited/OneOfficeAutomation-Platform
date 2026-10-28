# 05 Work queues

## What this covers

Work queues hold items of work (invoices, tickets, orders) that one agent processes one item per run on a runner pool.
This document specifies queues and their settings, work item models, how items are added, the item states and their
transitions, the service level, attempts and retries, deferral, automatic processing, the validation of items an
agent was unsure about, queue members, export and import, retention and permissions.

## Contents

1. Queues
2. Work item models
3. Adding items
4. Item states
5. Processing
6. Attempts, retries and deferral
7. Service level
8. Validation of uncertain items (To validate)
9. Changing, deleting and exporting items
10. Queue members
11. Export and import of a queue definition
12. Retention
13. Permissions and audit

## 1. Queues

| Field | Rule |
| --- | --- |
| Name | 1-120 characters, unique (on import) |
| Description | Free text |
| Agent | The agent that processes items; its live production version runs. Required before processing can start. |
| Runner pool | The pool whose runners process items. Required before processing can start. |
| Attempts per item | 1-10 (default 1) |
| Service level (minutes) | Optional; items waiting longer count as overdue |
| Work item model | Optional; can only be changed while the queue has no items |
| Processing | On or off; off for a new queue |

The Work queues page lists each queue with its agent, pool, waiting items (new and retry), items in progress, done
(succeeded / failed), service level with the number overdue, and whether processing is on. A queue that has an item in
progress cannot be deleted (`CR.CONFLICT`: stop processing and wait); deleting a queue removes its items and members.

## 2. Work item models

A model (Work item models) lists the fields every item of a queue has.

| Rule | Detail |
| --- | --- |
| Name | 1-120 characters |
| Fields | 1 to 100; each has a name (starts with a letter or `_`, then letters, digits, `_`, up to 64), a type (`text`, `number`, `boolean`, `date`), required or not, and whether it is shown in the item list |
| Item checks | Required fields present and not empty; values of the field's type; no fields the model does not have (`CR.BAD_ITEM`, naming the item and field) |
| Conversions | Numbers from text; true/false from `true`, `yes`, `1`, `false`, `no`, `0`; dates as `YYYY-MM-DD` (a longer date-time keeps its text if the date part is valid) |
| Changes while items use it | Optional fields may be added and labels changed; fields cannot be removed, change type, or become required (`CR.CONFLICT`) |
| Delete | Refused while queues use the model (`CR.IN_USE`) |

Without a model, item data is taken as given.

## 3. Adding items

| Way | Rules |
| --- | --- |
| Paste JSON | A list of objects; each may carry `_reference` and `_priority` |
| CSV (paste or file) | First row names the fields; a `reference` column names each item and a `priority` column (whole number) orders it; both are kept apart from the data |
| Excel workbook (.xlsx) | The first sheet or the named one; first row as for CSV; cells are read as shown in Excel, dates as `yyyy-mm-dd` |
| API | `POST /api/queues/{id}/items`, `/items/csv` (CSV text), `/items/xlsx` (workbook in Base64) |
| From an agent | The work queue toolkit (`ooa.workload`) |

At most 10,000 items are added at a time (`CR.TOO_LARGE`). All items of one addition are checked and stored together,
or none. A file needs a header row and at least one item. New items have status `new`, priority 0 unless given, and 0 attempts.

## 4. Item states

| State (stored) | Shown as | Meaning |
| --- | --- | --- |
| `new` | New | Waiting to be processed |
| `in_progress` | In progress | A run is working on it |
| `retry` | Retry | Failed, with attempts left; waiting to be processed again |
| `review` | To validate | The agent asked a person to check what it read (section 8) |
| `success` | Succeeded | Processed; the agent's outputs are kept as the result |
| `failed` | Failed | Failed with no attempts left, stopped, rejected in validation, or its run could not start |
| `deferred` | Deferred | Put aside by a person; processed again after its time if one was given, otherwise only after a Retry |

Transitions:

| From | To | When |
| --- | --- | --- |
| new, retry | in_progress | Processing gives it to an idle runner (attempts + 1) |
| deferred (with a time that has passed) | in_progress | Processing gives it to an idle runner (attempts + 1; the time is cleared) |
| in_progress | success | The run succeeded |
| in_progress | review | The run succeeded and returned an output `validation` holding `fields` |
| in_progress | retry | The run failed (not stopped) and attempts used < attempts per item |
| in_progress | failed | The run failed with no attempts left, or was stopped, or the runner was lost on the last attempt |
| in_progress | failed | The run could not be started (the error is kept; processing of that queue pauses until the next tick) |
| review | new | A person approved it (with checked values) |
| review | failed | A person rejected it (`VALIDATION.REJECTED`) |
| failed, deferred, new, retry | new | **Retry** by a person: attempts reset to 0 |
| failed, deferred, new, retry | deferred | **Defer** by a person |

## 5. Processing

- **Start processing** needs an agent and a runner pool (`CR.BAD_INPUT` otherwise). The person who starts it becomes
  the queue's run-as user: runs are requested by them, with their role, permissions and credentials. Stop processing
  turns it off; items in progress finish.
- The leading Command Center node gives work every few seconds (setting `queue_poll_seconds`, 3-3600, default 15) and
  again at once when a queue run ends.
- Each idle online runner of the pool (nothing queued or running on it) gets the next item: highest priority first,
  then oldest.
- The agent receives the item's data in the input `work_item` (a dictionary) and its id in `work_item_id`, when it
  declares those inputs. Its outputs are stored as the item's result; its error as the item's error, with a link to the run.

## 6. Attempts, retries and deferral

- Attempts per item (1-10) is the total number of runs an item may get. A failed run with attempts left puts the item
  in `retry`, so it is picked up again automatically.
- A stopped run never leads to a retry.
- A person can **Retry** failed or deferred items (attempts start again from 0), and **Defer** waiting or failed items
  to put them aside, optionally until a time (`until`, an ISO time): once that time has passed, processing picks the
  item up again by itself. Deferred items without a time are only processed after a Retry.

## 7. Service level

When a queue has a service level, items in `new`, `in_progress` or `retry` created longer ago than that many minutes
count as **overdue**. The count is shown in the queue list. Overdue items are not reprioritised automatically.

## 8. Validation of uncertain items (To validate)

An agent that is unsure of what it read (for example Extract fields with *Min confidence*, which returns
`needs_review` and `validation`) returns an output named `validation` with its `fields` and confidences. The item then
waits in `review` (To validate).

- **Validate** shows each field with how sure the agent was; the unsure ones are highlighted.
- **Approve** (with corrections): only the fields the agent asked about are taken; the item goes back to `new` with
  `validated_fields`, `validated: true` and `validated_by` added to its data, so the agent's next run uses them. The
  audit entry lists which fields were changed.
- **Reject** needs a reason; the item fails with `VALIDATION.REJECTED` ("Rejected by NAME").
- Validating an item not in `review` is refused (`CR.CONFLICT`). Needs `queues.work` and the consumer place on a queue with members.
- API: `POST /api/queues/{id}/items/{item}/validate` with `{fields}` or `{reject: true, reason}`.

## 9. Changing, deleting and exporting items

- **Open** an item to change its data (checked against the model), reference or priority; refused while it is in progress.
- **Delete** removes items that are not in progress.
- Items can be filtered by status and searched by reference or data, 1-500 per page.
- **Export CSV** downloads the items (optionally by status): reference, priority, status, attempts, dates, then the
  data fields. Cells a spreadsheet would read as a formula (starting with `=`, `+`, `-`, `@`, unless a plain number)
  are prefixed with `'`.

## 10. Queue members

Without members, roles alone decide who may use a queue. With members, only they may (plus people with `queues.all`):

| Member place | May |
| --- | --- |
| Owner | Everything: settings, members, items, processing |
| Participant | See the queue, add, change and delete items |
| Consumer | See the queue, start and stop processing, retry and defer items, validate items |

- Members are users, roles or teams (`user:`, `role:`, `team:`).
- A queue with members needs at least one owner, and the person saving the list must stay an owner (themselves or
  through a role or team) unless they have `queues.all`.
- A refused action names the place needed ("Ask an owner of the queue to add you as a consumer").

## 11. Export and import of a queue definition

- **Export definition** writes the queue's settings, work item model and members, and optionally its waiting items
  (new, retry and deferred; deferred ones come back as new), up to 10,000 items. The runner pool is written by name.
- **Import queue** makes a new queue, optionally under another name (an existing name is refused). The model is reused
  when one with the same name and fields exists, otherwise made. The agent and pool are linked when they exist here;
  members whose users, roles or teams do not exist are left out. Everything missing is listed.

## 12. Retention

`work_item_retention_days` (Administration > Settings, 0 keeps them) removes succeeded and failed items whose
processing ended longer ago than that, once an hour; the removal is audited as `queue.items_purged`.

## 13. Permissions and audit

| Action | Permission (and member place, when the queue has members) |
| --- | --- |
| See queues, items, models | `queues.view` (any member place) |
| Create, change, delete queues and models; set members; import and export | `queues.manage` (owner) |
| Add, change, delete items | `queues.work` (participant) |
| Start and stop processing, retry, defer, validate | `queues.work` (consumer) |
| See and use every queue regardless of members | `queues.all` |

Audited: `queue.save`, `queue.delete`, `queue.start`, `queue.stop`, `queue.add_items`, `queue.items_retry`,
`queue.items_defer`, `queue.items_delete`, `queue.item_update`, `queue.item_review`, `queue.item_validate`,
`queue.item_reject`, `queue.members`, `queue.model_save`, `queue.model_delete`, `queue.dispatch` (failed starts).

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [09 AI features](09-ai-features.md) (document extraction and confidence)
- [10 Users, roles, teams and security](10-users-roles-teams-and-security-features.md)
- [Command Center and runner, section 6](../CONTROL_ROOM_AND_AGENT.md)
