# Work queues

Toolkit `ooa.workload`, version 1.0.0. Add work items to a queue, take the next item, and mark items completed, failed or deferred with retries.

[All toolkits](README.md)

## Contents

- **Queue**: Add work item, Add work items from table, Get queue counts, Get next item, List items
- **Item**: Mark completed, Defer item, Mark failed, Get work item, Update work item
- **Conditions**: Queue has items
- **Loop types**: Each available work item

## Queue

### Add work item

`ooa.workload/add_item` (action)

Add one item (a Dictionary of fields) to a queue.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. Example: `invoices` |
| Data | `data` | Dictionary | Yes |  | Main | Item fields, e.g. {"invoice": "INV-7", "amount": 120}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | New item id. |

*Time limit 60 s.*

### Add work items from table

`ooa.workload/add_items` (action)

Add one item per table row (columns become fields).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Table | `table` | Table | Yes |  | Main | Rows to add. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Items added. |

*Time limit 60 s.*

### Get queue counts

`ooa.workload/counts` (action)

Count items by status.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `counts` | Dictionary | new, in_progress, completed, failed, deferred, total. |

*Time limit 60 s; safe to repeat.*

### Get next item

`ooa.workload/get_next` (action)

Take the oldest available item (new, or deferred and due) and mark it in progress; found is false when the queue is empty.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when an item was taken. |
| `item_id` | Text | Item id. |
| `data` | Dictionary | Item fields. |
| `attempt` | Number | Which attempt this is (1 = first). |

*Time limit 60 s.*

### List items

`ooa.workload/list_items` (action)

List items, optionally only one status, as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Status | `status` | Text | No |  | Main | Only this status. One of: `new`, `in_progress`, `completed`, `failed`, `deferred` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, status, attempts, updated, error, data. |

*Time limit 60 s; safe to repeat.*

## Item

### Mark completed

`ooa.workload/complete` (action)

Mark an item completed, optionally storing result fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Result | `result` | Dictionary | No |  | Main | Result fields to keep with the item. |

*Time limit 60 s.*

### Defer item

`ooa.workload/defer` (action)

Put an item aside until a date and time (UTC), e.g. when a supplier system is closed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Until | `until` | DateTime | Yes |  | Main | When it becomes available again. |

*Time limit 60 s.*

### Mark failed

`ooa.workload/fail` (action)

Record a failure; the item goes back to the queue for another attempt until max_attempts is reached.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Error | `error` | Text | No | (empty) | Main | What went wrong (shown to operators). |
| Maximum attempts | `max_attempts` | Number | No | `3` | More options | Total attempts allowed. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `will_retry` | Yes/No | True when the item was put back for another attempt. |

*Time limit 60 s.*

### Get work item

`ooa.workload/get_item` (action)

Read one work item's fields, status, attempts and result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item` | Dictionary | id, status, attempts, data, result, error, created, updated, defer_until. |

*Time limit 60 s; safe to repeat.*

### Update work item

`ooa.workload/update_item` (action)

Change the fields of a work item and optionally send it back for processing or put it on hold (until a time or indefinitely).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Data | `data` | Dictionary | No |  | Main | Fields to change; other fields keep their values unless replace_data is on. |
| Status | `status` | Text | No | `unchanged` | Main | reprocess: back to new so it is taken again (attempts restart); on_hold: put aside until on_hold_until, or until changed again. One of: `unchanged`, `reprocess`, `on_hold` |
| On hold until | `on_hold_until` | DateTime | No |  | Main | When an on-hold item becomes available again (UTC); held indefinitely when empty. Shown when: `{"status": ["on_hold"]}` |
| Replace data | `replace_data` | Yes/No | No | No | More options | Replace all fields with data instead of merging. |
| Result | `result` | Dictionary | No |  | More options | Result fields to keep with the item. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | The item's status after the change. |

*Time limit 60 s.*

## Conditions

Used in If, Loop while and Wait until steps.

### Queue has items

`ooa.workload/has_items` (condition)

True when the queue has items waiting (new, or deferred and due).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |

## Loop types

Used as the kind of loop in a Loop step.

### Each available work item

`ooa.workload/each_item` (loop type)

Take items one by one until the queue is empty; mark each completed or failed inside the loop.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Maximum items | `max_items` | Number | No | `1000` | Main | Stop after this many items. Range: 1 to  |
