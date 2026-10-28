# Number

Toolkit `ooa.number`, version 1.0.0. Round, format and generate numbers with exact decimal arithmetic.

[All toolkits](README.md)

## Contents

- **Number**: Decrement, Format number, Increment, Random number, Round, Number to text

## Number

### Decrement

`ooa.number/decrement` (action)

Subtract a step (1 by default) from a number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Starting number. |
| Step | `step` | Number | No | `1` | Main | Amount to subtract. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Number minus the step. |

*Time limit 60 s; safe to repeat.*

### Format number

`ooa.number/format` (action)

Format a number as text with thousands separators and fixed decimals, e.g. 1,234.50.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to format. |
| Decimals | `decimals` | Number | No | `2` | Main | Decimal places. Range: 0 to 20 |
| Thousands separator | `thousands_separator` | Yes/No | No | Yes | Main | Group thousands. |
| Locale | `locale` | Text | No | `en-US` | More options | Locale for separators, e.g. en-US, de-DE, en-IN. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Formatted text. |

*Time limit 60 s; safe to repeat.*

### Increment

`ooa.number/increment` (action)

Add a step (1 by default) to a number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Starting number. |
| Step | `step` | Number | No | `1` | Main | Amount to add. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Number plus the step. |

*Time limit 60 s; safe to repeat.*

### Random number

`ooa.number/random` (action)

Generate a random number between min and max (inclusive), whole or with decimals.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Smallest value | `min` | Number | No | `0` | Main | Smallest value. |
| Largest value | `max` | Number | No | `100` | Main | Largest value. |
| Decimals | `decimals` | Number | No | `0` | Main | Decimal places of the result; 0 gives a whole number. Range: 0 to 15 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Random number. |

*Time limit 60 s.*

### Round

`ooa.number/round` (action)

Round a number to a number of decimal places.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to round. |
| Decimals | `decimals` | Number | No | `0` | Main | Decimal places. |
| Mode | `mode` | Text | No | `half_up` | Main | Rounding mode; half_even is banker's rounding. One of: `half_up`, `half_even`, `up`, `down` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Rounded number. |

*Time limit 60 s; safe to repeat.*

### Number to text

`ooa.number/to_text` (action)

Write a number as plain text, with fixed decimals or a pattern such as 0000 or #,##0.00.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to write. |
| Decimals | `decimals` | Number | No |  | Main | Fixed decimal places (rounded half up). Range: 0 to 20 |
| Pattern | `pattern` | Text | No |  | More options | Pattern with 0 for required digits and # for optional ones, e.g. 00000 or #,##0.00. |
| Rounding | `rounding` | Text | No | `half_up` | More options | How extra decimals are dropped: rounded half up, or cut off (truncated). One of: `half_up`, `truncate`; Shown when: `{"pattern": [""]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The number as text. |

*Time limit 60 s; safe to repeat.*
