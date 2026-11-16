# Boolean

Toolkit `ooa.boolean`, version 1.1.0. Set, combine, check, convert and invert Yes/No (true/false) values.

[All toolkits](README.md)

## Contents

- **Boolean**: Combine Yes/No values, Compare, Number to Yes/No, Text to Boolean, Invert, Set Yes/No, Boolean to number, Boolean to text
- **Conditions**: Is No (false), Is Yes (true)

## Boolean

### Combine Yes/No values

`ooa.boolean/combine` (action)

One Yes/No from several: all of them (and), any of them (or), exactly one (xor) or none of them.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Values | `values` | List | Yes |  | Main | The Yes/No values, e.g. [$paid$, $shipped$, $invoiced$]. |
| How | `how` | Text | No | `all` | Main | All: every one is Yes (and). Any: at least one (or). Exactly one: one and only one (xor). None: not one. One of: `all`, `any`, `exactly_one`, `none` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | The combined value. |
| `true_count` | Number | How many of the values are Yes. |

*Time limit 60 s; safe to repeat.*

### Compare

`ooa.boolean/compare` (action)

Check whether two true/false values are equal or not equal.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Yes/No | Yes |  | Main | First value. |
| Other | `other` | Yes/No | Yes |  | Main | Second value. |
| Operator | `operator` | Text | No | `equal` | Main | equal or not_equal. One of: `equal`, `not_equal` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | Result of the comparison. |
| `order` | Number | Ordering of the two values: 1 when only the first is true, -1 when only the second is, 0 when equal. |

*Time limit 60 s; safe to repeat.*

### Number to Yes/No

`ooa.boolean/from_number` (action)

Read a number as Yes/No: 0 is No and any other number Yes, or strictly 1 Yes and 0 No.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | The number. |
| Rule | `rule` | Text | No | `non_zero` | Main | Non-zero: 0 is No, anything else Yes. One or zero: 1 is Yes, 0 is No, other numbers fail with BOOLEAN.INVALID. One of: `non_zero`, `one_or_zero` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | The Yes/No value. |

*Time limit 60 s; safe to repeat.*

### Text to Boolean

`ooa.boolean/from_text` (action)

Read true/false, yes/no, 1/0 (any case) from text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text such as Yes or false. |
| Strict | `strict` | Yes/No | No | Yes | More options | Fail on text that is not a yes/no value; when off, such text (and empty text) gives false. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | Parsed value. |

*Time limit 60 s; safe to repeat.*

### Invert

`ooa.boolean/invert` (action)

Return the opposite value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Yes/No | Yes |  | Main | Value to invert. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | Opposite value. |

*Time limit 60 s; safe to repeat.*

### Set Yes/No

`ooa.boolean/set` (action)

Work out a Yes/No value: from a value (yes, 1, true, a non-empty text) or from a formula such as $total$ > 1000 and $country$ == 'IN'.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice How = From a value | A Yes/No, a number (0 is No), or a text such as yes, no, true, false, 1, 0. |
| Empty is | `empty_is` | Text | No | `no` | Main; choice How = From a value | What an empty value means, and what other text means (any text other than a no-word is Yes when this is no or yes). One of: `no`, `yes`, `error` |
| Formula | `formula` | Any | No |  | Main; choice How = From a formula | A condition with variables, e.g. $total$ > 1000 and $country$ == 'IN', or contains($subject$, 'urgent'). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | The Yes/No value. |

*Time limit 60 s; safe to repeat.*

### Boolean to number

`ooa.boolean/to_number` (action)

Turn true into 1 and false into 0.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Yes/No | Yes |  | Main | Value. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | 1 or 0. |

*Time limit 60 s; safe to repeat.*

### Boolean to text

`ooa.boolean/to_text` (action)

Turn a Boolean into custom text, e.g. Approved/Rejected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Yes/No | Yes |  | Main | Value. |
| True text | `true_text` | Text | No | `true` | More options | Text when true. |
| False text | `false_text` | Text | No | `false` | More options | Text when false. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Text for the value. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Is No (false)

`ooa.boolean/is_false` (condition)

True when the value is No: false, no, 0, empty or missing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main | The value to check. |

### Is Yes (true)

`ooa.boolean/is_true` (condition)

True when the value is Yes: true, yes, 1, or a non-empty text other than a no-word.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main | The value to check. |
