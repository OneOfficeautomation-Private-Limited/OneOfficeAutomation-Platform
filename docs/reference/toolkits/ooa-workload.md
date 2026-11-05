# Work queues

Toolkit `ooa.workload`, version 1.0.0. Add work items to a queue, take the next item, and mark items completed, failed or deferred with retries.

[All toolkits](README.md)

## Contents

- **Queue**: Add work item, Add work items from table, Copy work items, Get queue counts, Delete work items, Get next item, List items, Move work items
- **Item**: Mark completed, Defer item, Mark failed, Get work item, Tag work item, Unlock work item, Remove tags from work item, Update work item
- **Conditions**: Queue has items
- **Loop types**: Each available work item

## Queue

### Add work item

`ooa.workload/add_item` (action)

Add one item (a Dictionary of fields) to a queue, optionally with a reference that is unique in the queue and tags.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. Example: `invoices` |
| Data | `data` | Dictionary | Yes |  | Main | Item fields, e.g. {"invoice": "INV-7", "amount": 120}. |
| Reference | `reference` | Text | No |  | More options | A name for the item that is unique in the queue, e.g. the invoice number. |
| If exists | `if_exists` | Text | No | `error` | More options | When an item with this reference is already there: error stops, skip leaves it, update gives it the new data and tags. One of: `error`, `skip`, `update` |
| Tags | `tags` | List | No |  | More options | Labels to find the item by, e.g. ["urgent", "north"]. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | New item id (or the id of the item with the same reference). |
| `outcome` | Text | added, skipped (the reference was there and if_exists is skip) or updated. |

*Time limit 60 s.*

### Add work items from table

`ooa.workload/add_items` (action)

Add one item per table row (columns become fields), optionally with a reference column and tags.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Table | `table` | Table | Yes |  | Main | Rows to add. |
| Reference column | `reference_column` | Text | No |  | More options | The column that holds each row's reference (unique in the queue); it stays a field too. |
| If exists | `if_exists` | Text | No | `error` | More options | When a row's reference is already in the queue: error stops (nothing is added), skip leaves it out, update changes that item. One of: `error`, `skip`, `update` |
| Tags | `tags` | List | No |  | More options | Labels every new item gets. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Items added (or updated). |
| `skipped` | Number | Rows left out because their reference was there and if_exists is skip. |

*Time limit 60 s.*

### Copy work items

`ooa.workload/copy_items` (action)

Copy items to another queue as new items (new ids, same data, reference and tags); items whose reference the other queue has already are skipped.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue the items are in. |
| To queue | `to_queue` | Text | Yes |  | Main | Queue to copy them to. |
| Status | `status` | Text | No |  | More options | Only items of this status. One of: `new`, `completed`, `failed`, `deferred` |
| Tags | `tags` | List | No |  | More options | Only items that have all these tags. |
| Item IDs | `item_ids` | List | No |  | More options | Only these items. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Items copied. |
| `skipped` | Number | Items left out because the other queue has their reference. |

*Time limit 60 s.*

### Get queue counts

`ooa.workload/counts` (action)

Count items by status.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `counts` | Dictionary | new, in_progress, completed, failed, deferred, total (a central queue: its own statuses). |

*Time limit 60 s; safe to repeat.*

### Delete work items

`ooa.workload/delete_items` (action)

Delete items by status, age, tags or ids. Only completed and failed items are deleted unless force is on (items in progress never are, unless forced).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Status | `status` | Text | No | `finished` | Main | finished: completed and failed items; any: every status (needs force for the others). One of: `finished`, `completed`, `failed`, `new`, `deferred`, `in_progress`, `any` |
| Older than (hours) | `older_than_hours` | Number | No | `0` | Main | Only items last changed more than this many hours ago (0: any age). Range: 0 to  |
| Tags | `tags` | List | No |  | More options | Only items that have all these tags. |
| Item IDs | `item_ids` | List | No |  | More options | Only these items. |
| Force | `force` | Yes/No | No | No | More options | Also delete items that are new, deferred or in progress. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Items deleted. |

*Time limit 60 s.*

### Get next item

`ooa.workload/get_next` (action)

Take the oldest available item (new, or deferred and due), optionally only one with every tag given, and mark it in progress; with wait_seconds it waits for an item to arrive. found is false when there is none.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Tags | `tags` | List | No |  | More options | Only an item that has all these tags. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | More options | When the queue has no item, keep looking for this many seconds (stopping the run ends the wait). Range: 0 to 3600 |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when an item was taken. |
| `item_id` | Text | Item id. |
| `data` | Dictionary | Item fields. |
| `attempt` | Number | Which attempt this is (1 = first). |
| `reference` | Text | The item's reference (empty when it has none). |
| `tags` | List | The item's tags. |

*Time limit 3660 s.*

### List items

`ooa.workload/list_items` (action)

List items, optionally only one status or only items with every tag given, as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Status | `status` | Text | No |  | Main | Only this status. One of: `new`, `in_progress`, `completed`, `failed`, `deferred` |
| Tags | `tags` | List | No |  | More options | Only items that have all these tags. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, status, attempts, updated, error, data, reference, tags, error_kind. |

*Time limit 60 s; safe to repeat.*

### Move work items

`ooa.workload/move_items` (action)

Move items to another queue as new items (new ids, same data, reference and tags); items in progress, and items whose reference the other queue has, stay.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue the items are in. |
| To queue | `to_queue` | Text | Yes |  | Main | Queue to move them to. |
| Status | `status` | Text | No |  | More options | Only items of this status. One of: `new`, `completed`, `failed`, `deferred` |
| Tags | `tags` | List | No |  | More options | Only items that have all these tags. |
| Item IDs | `item_ids` | List | No |  | More options | Only these items. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Items moved. |
| `skipped` | Number | Items left where they were because the other queue has their reference. |

*Time limit 60 s.*

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
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

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
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

*Time limit 60 s.*

### Mark failed

`ooa.workload/fail` (action)

Record a failure. A system exception (the default) puts the item back for another attempt until max_attempts is reached; a business exception (the item itself is wrong) fails it at once, without retries.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Error | `error` | Text | No | (empty) | Main | What went wrong (shown to operators). |
| Kind | `kind` | Text | No | `system` | Main | system: something around the item failed (a system was down), retried; business: the item breaks a business rule, not retried. One of: `system`, `business` |
| Maximum attempts | `max_attempts` | Number | No | `3` | More options | Total attempts allowed (a central queue uses its own setting). Range: 1 to  |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `will_retry` | Yes/No | True when the item was put back for another attempt. |

*Time limit 60 s.*

### Get work item

`ooa.workload/get_item` (action)

Read one work item's fields, status, attempts, result, reference, tags and error kind.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item` | Dictionary | id, status, attempts, data, result, error, error_kind, reference, tags, created, updated, defer_until. |

*Time limit 60 s; safe to repeat.*

### Tag work item

`ooa.workload/tag_item` (action)

Add tags to a work item (tags it has already are kept).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Tags | `tags` | List | Yes |  | Main | Tags to add, e.g. ["urgent"]. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tags` | List | The item's tags after the change. |

*Time limit 60 s.*

### Unlock work item

`ooa.workload/unlock_item` (action)

Release an item stuck in progress (its agent stopped without marking it): it becomes new again and keeps its attempts.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

*Time limit 60 s.*

### Remove tags from work item

`ooa.workload/untag_item` (action)

Remove tags from a work item (ignoring case).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Queue | `queue` | Text | Yes |  | Main | Queue name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Tags | `tags` | List | Yes |  | Main | Tags to remove. |
| Central | `central` | Yes/No | No | No | More options | Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tags` | List | The item's tags after the change. |

*Time limit 60 s.*

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
