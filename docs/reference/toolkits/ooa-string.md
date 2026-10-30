# String

Toolkit `ooa.string`, version 1.0.0. Text manipulation: extract, replace, split, change case.

[All toolkits](README.md)

## Contents

- **Text**: Change case, Compare, Extract text, Find, Get length, Random text, Replace, Reverse, Split, Get part of text, Number to locale text, Text to number, Trim
- **Conditions**: Text is an email address, Text is a number

## Text

### Change case

`ooa.string/change_case` (action)

Convert text to upper, lower, title or sentence case.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Case | `case` | Text | Yes |  | Main | Target case. One of: `upper`, `lower`, `title`, `sentence` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Converted text. |

*Time limit 60 s; safe to repeat.*

### Compare

`ooa.string/compare` (action)

Compare two texts exactly, ignoring case, or in natural order where numbers inside the text are compared by value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | First text. |
| Other | `other` | Text | Yes |  | Main | Second text. |
| Mode | `mode` | Text | No | `exact` | Main | exact, ignore_case, or natural (file2 before file10, case ignored). One of: `exact`, `ignore_case`, `natural` |
| Ignore line breaks | `ignore_line_breaks` | Yes/No | No | No | More options | Treat each line break (\r\n or \n) as a space, so text wrapped onto several lines still matches. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | -1 when the first text comes first, 0 when they are equal, 1 when it comes after. |
| `equal` | Yes/No | True when the texts are equal under the chosen mode. |

*Time limit 60 s; safe to repeat.*

### Extract text

`ooa.string/extract_text` (action)

Return the text before, after or between markers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| After | `after` | Text | No |  | Main | Return text after this marker. |
| After occurrence | `after_occurrence` | Number | No | `1` | Main | Which occurrence of the 'after' marker to start from (1 = first). Range: 1 to ; Shown when: `{"after": ["!", ""]}` |
| Before | `before` | Text | No |  | Main | Return text before this marker. |
| Before occurrence | `before_occurrence` | Number | No | `1` | Main | Which occurrence of the 'before' marker to stop at (1 = first). Range: 1 to ; Shown when: `{"before": ["!", ""]}` |
| If not found | `if_not_found` | Text | No | `empty` | Main | Result when a marker is missing: empty text, or the whole source text. One of: `empty`, `source` |
| Maximum length | `max_length` | Number | No |  | Main; tick Limit the length | Keep at most this many characters of the result. Range: 0 to  |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Find the markers without regard to upper/lower case. |
| Trim | `trim` | Yes/No | No | Yes | Main | Trim whitespace from the result. |
| Remove line breaks | `remove_line_breaks` | Yes/No | No | No | Main | Remove line breaks from the result. |
| Until | `until` | Text | No | `marker` | More options | Where the result ends when the 'before' marker is empty or not found: as before (marker), at the end of the line, or at the end of the text. One of: `marker`, `end_of_line`, `end_of_text` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Extracted text, empty when a marker is missing. |

*Time limit 60 s; safe to repeat.*

### Find

`ooa.string/find` (action)

Find where a text (or regular expression) first appears, starting from a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to search in. |
| Find | `find` | Text | Yes |  | Main | Text or regular expression to look for. |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Ignore upper/lower case. |
| Find is a regular expression | `regex` | Yes/No | No | No | Main | Treat 'find' as a regular expression. |
| Start | `start` | Number | No | `0` | Main | Zero-based position to start searching from. Range: 0 to  |
| Occurrence | `occurrence` | Number | No | `1` | More options | Which match to return (1 = first, 2 = second ...). Range: 1 to  |
| Group | `group` | Number | No | `0` | More options | Regular expression: return this capture group in match and all_matches instead of the whole match, e.g. 1 for the number in Total: (\d+). Range: 0 to ; Shown when: `{"regex": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `index` | Number | Zero-based position of the match, or -1 when not found. |
| `found` | Yes/No | True when a match was found. |
| `match` | Text | The matched text (or the chosen group), empty when not found. |
| `position` | Number | One-based position of the match, or 0 when not found. |
| `groups` | List | Regular expression: the capture groups of the returned match (group 1 first); empty otherwise. |
| `named_groups` | Dictionary | Regular expression: named groups (?<name>...) of the returned match. |
| `all_matches` | List | Every match from the start position on, in order. |
| `count` | Number | How many matches there are from the start position on. |

*Time limit 60 s; safe to repeat.*

### Get length

`ooa.string/length` (action)

Count the characters in a text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to measure. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `length` | Number | Number of characters. |

*Time limit 60 s; safe to repeat.*

### Random text

`ooa.string/random_string` (action)

Generate random text of a given length from letters, digits and/or symbols.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Length | `length` | Number | No | `12` | Main | Number of characters. Range: 1 to 100000 |
| Letters | `letters` | Yes/No | No | Yes | More options | Include letters A-Z and a-z. |
| Digits | `digits` | Yes/No | No | Yes | More options | Include digits 0-9. |
| Symbols | `symbols` | Yes/No | No | No | More options | Include symbols such as ! # $ %. |
| Letter case | `letter_case` | Text | No | `mixed` | More options | Letters in mixed, upper or lower case. One of: `mixed`, `upper`, `lower`; Shown when: `{"letters": ["true"]}` |
| Exclude characters | `exclude_characters` | Text | No |  | More options | Characters never to use, e.g. 0O1l to avoid look-alikes. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Random text. |

*Time limit 60 s.*

### Replace

`ooa.string/replace` (action)

Replace text, optionally using a regular expression.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Find | `find` | Text | Yes |  | Main | Text or regex to find. |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Match without regard to upper/lower case. |
| Find is a regular expression | `regex` | Yes/No | No | No | Main | Treat 'find' as a regular expression. |
| Start | `start` | Number | No | `0` | Main | Zero-based position to start replacing from; text before it is kept as is. Range: 0 to  |
| Count | `count` | Number | No | `-1` | Main | Replace at most this many matches; -1 replaces all. Range: -1 to  |
| Replace with | `replace_with` | Text | No | (empty) | Main | Replacement text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Text after replacement. |

*Time limit 60 s; safe to repeat.*

### Reverse

`ooa.string/reverse` (action)

Return the text with its characters in reverse order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Reversed text. |

*Time limit 60 s; safe to repeat.*

### Split

`ooa.string/split` (action)

Split text into a list by a delimiter.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Delimiter | `delimiter` | Text | Yes |  | Main | Delimiter, e.g. ','. |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Match a letter delimiter without regard to upper/lower case. Shown when: `{"regex": ["false"]}` |
| Maximum parts | `max_parts` | Number | No | `0` | Main | Split into at most this many parts; the last part keeps the rest. 0 splits at every delimiter. Range: 0 to  |
| Trim items | `trim_items` | Yes/No | No | Yes | More options | Trim each item. |
| Find is a regular expression | `regex` | Yes/No | No | No | More options | Treat the delimiter as a regular expression, e.g. \R for any line break. |
| Remove empty | `remove_empty` | Yes/No | No | No | More options | Leave out empty parts. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `items` | List | List of parts. |

*Time limit 60 s; safe to repeat.*

### Get part of text

`ooa.string/substring` (action)

Return the part of a text from a start position, for a length or up to an end position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Start | `start` | Number | No | `0` | Main | Zero-based start position. Range: 0 to  |
| Length | `length` | Number | No |  | Main; choice Take = A number of characters | Number of characters to take; to the end when empty. Range: 0 to  |
| End | `end` | Number | No |  | Main; choice Take = Up to a position | Zero-based end position (not included); used when length is empty. Range: 0 to  |
| If out of range | `if_out_of_range` | Text | No | `error` | Main | When the start is past the end of the text: fail with STRING.BAD_POSITION, or return empty text. One of: `error`, `empty` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The selected part of the text. |

*Time limit 60 s; safe to repeat.*

### Number to locale text

`ooa.string/to_locale_number` (action)

Write a number as text using the separators of a locale, e.g. 1.234,5 for de-DE.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Number | Yes |  | Main | Number to write. |
| Locale | `locale` | Text | No | `en-US` | Main | Locale, e.g. en-US, de-DE, en-IN. |
| Decimals | `decimals` | Number | No |  | More options | Fixed decimal places; keeps the number's own decimals when empty. Range: 0 to 20 |
| Grouping | `grouping` | Yes/No | No | Yes | More options | Use a thousands separator. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Formatted number. |

*Time limit 60 s; safe to repeat.*

### Text to number

`ooa.string/to_number` (action)

Read a number from text written for a locale, or with the decimal and grouping separators you give.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text such as 1,234.56 or 1.234,56. Example: `1.234,56` |
| Locale | `locale` | Text | No | `en-US` | More options | Locale the text is written in, e.g. en-US, de-DE, fr-FR. |
| Decimal separator | `decimal_separator` | Text | No |  | More options | Decimal separator; overrides the locale. |
| Grouping separator | `grouping_separator` | Text | No |  | More options | Thousands separator; overrides the locale. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | The number. |

*Time limit 60 s; safe to repeat.*

### Trim

`ooa.string/trim` (action)

Remove spaces, or the characters you list, from both ends, the start or the end of a text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Side | `side` | Text | No | `both` | Main | Which end to trim. One of: `both`, `left`, `right` |
| Characters | `characters` | Text | No |  | More options | Characters to remove, e.g. 0 or *-; whitespace when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Trimmed text. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Text is an email address

`ooa.string/is_email` (condition)

True when the text looks like an email address.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |

### Text is a number

`ooa.string/is_number` (condition)

True when the text is a number, e.g. 1,250.50 or -3.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
