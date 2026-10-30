# Boolean

Toolkit `ooa.boolean`, version 1.0.0. Convert and invert true/false values.

[All toolkits](README.md)

## Contents

- **Boolean**: Compare, Text to Boolean, Invert, Boolean to number, Boolean to text

## Boolean

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
