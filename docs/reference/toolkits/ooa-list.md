# List

Toolkit `ooa.list`, version 1.0.0. Add, read, remove, sort and join list items.

[All toolkits](README.md)

## Contents

- **List**: Add item, Append list, Clear, Get item, Find item, Join, Remove duplicates, Remove item, Reverse, Set item, Get size, Sort
- **Conditions**: List contains item, List is empty

## List

### Add item

`ooa.list/add_item` (action)

Add an item at the end, or at a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Item | `item` | Any | No |  | Main | Item to add. |
| Position | `position` | Number | No |  | Main; choice Add at = A position | Zero-based position; end of list when empty. Range: 0 to  |
| Items | `items` | List | No |  | More options | Several items to add in one go, after 'item' when both are given. |
| Skip existing | `skip_existing` | Yes/No | No | No | More options | Do not add an item that is already in the list. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Updated list. |

*Time limit 60 s; safe to repeat.*

### Append list

`ooa.list/append_list` (action)

Join two lists into one, the second after the first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | First list. |
| Other | `other` | List | Yes |  | Main | List to add at the end. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Combined list. |

*Time limit 60 s; safe to repeat.*

### Clear

`ooa.list/clear` (action)

Return an empty list; map it back to the variable to empty it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | No |  | Main | List to clear. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Empty list. |

*Time limit 60 s; safe to repeat.*

### Get item

`ooa.list/get_item` (action)

Read the item at a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Position | `position` | Number | Yes |  | Main | Zero-based position; negative counts from the end (-1 = last). |
| If out of range | `if_out_of_range` | Text | No | `error` | More options | When the position is outside the list: fail with LIST.INDEX, or return the default. One of: `error`, `default` |
| Default | `default` | Any | No |  | More options | Value returned for a position outside the list when if_out_of_range is default. Shown when: `{"if_out_of_range": ["default"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item` | Any | The item. |

*Time limit 60 s; safe to repeat.*

### Find item

`ooa.list/index_of` (action)

Return the zero-based position of the first (or last) item equal to a value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Value | `value` | Any | Yes |  | Main | Value to look for. |
| Ignore case | `ignore_case` | Yes/No | No | No | More options | Compare text without regard to upper/lower case. |
| Last | `last` | Yes/No | No | No | More options | Return the last matching position instead of the first. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `index` | Number | Zero-based position, or -1 when the value is not in the list. |
| `found` | Yes/No | True when the value is in the list. |

*Time limit 60 s; safe to repeat.*

### Join

`ooa.list/join` (action)

Join items into one text with a separator.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Separator | `separator` | Text | No | `, ` | Main | Separator; \n and \t stand for a line break and a tab. |
| Skip empty | `skip_empty` | Yes/No | No | No | More options | Leave out empty items. |
| Escape sequences | `escape_sequences` | Yes/No | No | No | More options | Turn \n, \r and \t in the separator into a line break, carriage return and tab. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Joined text. |

*Time limit 60 s; safe to repeat.*

### Remove duplicates

`ooa.list/remove_duplicates` (action)

Keep the first occurrence of each value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | List without duplicates. |

*Time limit 60 s; safe to repeat.*

### Remove item

`ooa.list/remove_item` (action)

Remove the item at a position, or every item equal to a value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Position | `position` | Number | No |  | Main; choice Remove by = Position | Position to remove. |
| Value | `value` | Any | No |  | Main; choice Remove by = Value | Remove every item equal to this value. |
| All occurrences | `all_occurrences` | Yes/No | No | Yes | Main; choice Remove by = Value | With a value: remove every equal item; when off, only the first. Shown when: `{"value": ["!", ""]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Updated list. |
| `removed` | Any | The removed item (by position), or a list of the removed items (by value). |
| `count` | Number | Number of items removed. |

*Time limit 60 s; safe to repeat.*

### Reverse

`ooa.list/reverse` (action)

Return the items in reverse order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Reversed list. |

*Time limit 60 s; safe to repeat.*

### Set item

`ooa.list/set_item` (action)

Replace the item at a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Position | `position` | Number | Yes |  | Main | Zero-based position. |
| Item | `item` | Any | No |  | Main | New item. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Updated list. |

*Time limit 60 s; safe to repeat.*

### Get size

`ooa.list/size` (action)

Number of items.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of items. |

*Time limit 60 s; safe to repeat.*

### Sort

`ooa.list/sort` (action)

Sort items (numbers numerically, text alphabetically).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | Source list. |
| Descending | `descending` | Yes/No | No | No | Main | Largest first. |
| Ignore case | `ignore_case` | Yes/No | No | Yes | More options | Ignore upper/lower case for text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `list` | List | Sorted list. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### List contains item

`ooa.list/contains` (condition)

True when the list contains the value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | List. |
| Value | `value` | Any | Yes |  | Main | Value to look for. |

### List is empty

`ooa.list/is_empty` (condition)

True when the list has no items.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| List | `list` | List | Yes |  | Main | List. |
