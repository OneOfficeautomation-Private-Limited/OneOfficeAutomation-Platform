# Number

Toolkit `ooa.number`, version 1.1.0. Calculate, round, format and generate numbers with exact decimal arithmetic.

[All toolkits](README.md)

## Contents

- **Number**: Calculate, Decrement, Format number, Format as currency or unit, Increment, Random number, Round, Round to a step, List statistics, Number to text, Number to words
- **Conditions**: Compare numbers, Number is between

## Number

### Calculate

`ooa.number/calculate` (action)

Work out a number: pick an operation (add, divide, power, round up, average, percent of ...) or write a formula such as ($price$ * $qty$) * (1 + $tax$ / 100).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Operation | `operation` | Text | No | `add` | Main; choice How = Pick an operation | What to work out. Percent of: A percent of B. Percentage change: from A to B, in percent. Round, round up and round down use Decimals. One of: `add`, `subtract`, `multiply`, `divide`, `remainder`, `power`, `square_root`, `absolute`, `negate`, `round`, `round_up`, `round_down`, `integer_part`, `decimal_part`, `minimum`, `maximum`, `sum`, `average`, `percent_of`, `percentage_change` |
| Number A | `a` | Number | No |  | Main; choice How = Pick an operation | The first number (the only one for square root, absolute, negate, round and the parts). Shown when: `{"operation": ["!", "minimum", "maximum", "sum", "average"]}` |
| Number B | `b` | Number | No |  | Main; choice How = Pick an operation | The second number: what is added, subtracted, multiplied by, divided by, the power, or the whole for Percent of. Shown when: `{"operation": ["add", "subtract", "multiply", "divide", "remainder", "power", "percent_of", "percentage_change"]}` |
| Numbers | `values` | List | No |  | Main; choice How = Pick an operation | The numbers, as a List variable or [1, 2, 3]; empty items are left out. Shown when: `{"operation": ["minimum", "maximum", "sum", "average"]}` |
| Formula | `formula` | Any | No |  | Main; choice How = Write a formula | A calculation with variables, brackets and functions, e.g. ($price$ * $qty$) * (1 + $tax$ / 100) or round(average($prices$), 2). |
| Decimals | `decimals` | Number | No |  | Main | Round the result half up to this many decimals; empty keeps it as it is (Round, Round up and Round down: 0 when empty). Range: 0 to 20 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | The result of the calculation. |

*Time limit 60 s; safe to repeat.*

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

### Format as currency or unit

`ooa.number/format_as` (action)

Write a number as money (1,234.50 EUR in the locale's way), as a percentage (15%) or with a unit (12.5 kg).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to format. |
| Style | `style` | Text | No | `currency` | Main | Money, percentage or a number with a unit. One of: `currency`, `percent`, `unit` |
| Currency | `currency` | Text | No | `USD` | Main | Three-letter currency code, e.g. USD, EUR, GBP, INR, JPY. Example: `EUR`; Shown when: `{"style": ["currency"]}` |
| Unit | `unit` | Text | No |  | Main | The unit, e.g. kg, hours, pcs, km/h. Example: `kg`; Shown when: `{"style": ["unit"]}` |
| Locale | `locale` | Text | No | `en-US` | Main | Locale for separators and symbol position, e.g. en-US, de-DE, en-IN, fr-FR. |
| Decimals | `decimals` | Number | No |  | More options | Decimal places; empty: the currency's own (2 for EUR, 0 for JPY), 0 for percent, as given for units. Range: 0 to 10 |
| Value is a fraction (0.15 is 15%) | `fraction` | Yes/No | No | Yes | More options | When ticked 0.15 shows as 15%; when not, 15 shows as 15%. Shown when: `{"style": ["percent"]}` |
| Unit before the number | `unit_before` | Yes/No | No | No | More options | Write the unit first, e.g. Rs 120 instead of 120 Rs. Shown when: `{"style": ["unit"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The formatted text. |

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

### Round to a step

`ooa.number/round_to_step` (action)

Round a number to the nearest step, e.g. prices to 0.05 or 0.99 endings, times to 15 minutes, quantities to a pack size.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to round. |
| Step | `step` | Number | No | `0.05` | Main | The step, e.g. 0.05, 0.5, 10 or 12 (a pack of twelve). |
| Direction | `direction` | Text | No | `nearest` | Main | To the nearest step (halves go up), always up, or always down. One of: `nearest`, `up`, `down` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | The rounded number. |

*Time limit 60 s; safe to repeat.*

### List statistics

`ooa.number/statistics` (action)

Count, sum, average, minimum, maximum and median of a list of numbers, in one step.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Numbers | `values` | List | Yes |  | Main | The numbers, as a List variable (a table column from Column to list) or [1, 2, 3]. |
| Skip text that is not a number | `skip_non_numbers` | Yes/No | No | No | More options | Leave out items that are not numbers instead of failing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | How many numbers. |
| `sum` | Number | Their total. |
| `average` | Number | Their average (up to 10 decimals). |
| `minimum` | Number | The smallest. |
| `maximum` | Number | The largest. |
| `median` | Number | The middle one (the average of the two middle ones for an even count). |

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

### Number to words

`ooa.number/to_words` (action)

Write a number in English words, for cheques and invoices: 1,234.50 as one thousand two hundred thirty-four dollars and fifty cents (lakh and crore for India).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to write in words. |
| Style | `style` | Text | No | `amount` | Main | Amount: whole units and two-digit cents (dollars and cents); plain: the number as it is, decimals read digit by digit (point two five). One of: `amount`, `plain` |
| Units | `major_unit` | Text | No | `dollars` | Main | Name of the whole units, e.g. dollars, euros, rupees; empty for none. Shown when: `{"style": ["amount"]}` |
| Hundredths | `minor_unit` | Text | No | `cents` | Main | Name of the hundredths, e.g. cents, paise; empty leaves them out. Shown when: `{"style": ["amount"]}` |
| System | `system` | Text | No | `international` | More options | International (thousand, million, billion) or Indian (thousand, lakh, crore). One of: `international`, `indian` |
| Letter case | `letter_case` | Text | No | `sentence` | More options | One thousand ..., one thousand ..., ONE THOUSAND ... or One Thousand .... One of: `sentence`, `lower`, `upper`, `title` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The number in words. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Compare numbers

`ooa.number/compare` (condition)

True when the comparison holds, e.g. Amount greater than or equal to 1000; with a tolerance, close numbers count as equal.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | The number to check. |
| Operator | `operator` | Text | No | `equal` | Main | How to compare. One of: `equal`, `not_equal`, `greater`, `greater_or_equal`, `less`, `less_or_equal` |
| Other | `other` | Number | Yes |  | Main | The number to compare with. |
| Tolerance | `tolerance` | Number | No | `0` | Main | Numbers this close count as equal, e.g. 0.01 for amounts in cents. Range: 0 to  |

### Number is between

`ooa.number/is_between` (condition)

True when the number lies between two others (the ends count unless you untick Include the ends).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to check. |
| Low | `low` | Number | Yes |  | Main | Lower end. |
| High | `high` | Number | Yes |  | Main | Upper end. |
| Include the ends | `inclusive` | Yes/No | No | Yes | Main | Count the ends themselves as between. |
