# String

Toolkit `ooa.string`, version 1.1.0. Text manipulation: extract, replace, split, join, clean, mask, fill templates, encode and change case.

[All toolkits](README.md)

## Contents

- **Text**: Change case, Compare, Count occurrences, Count words and lines, Encode or decode text, Escape for regex, Extract with pattern, Extract text, Fill a template, Find, Find all matches, Insert text, Join texts, Get length, Mask text, Normalize spaces, Pad text, Random text, Remove characters, Remove part of text, Repeat text, Replace, Reverse, Shorten text, Split, Get part of text, Number to locale text, Text to number, Trim
- **Conditions**: Text contains, Text ends with, Text is an email address, Text is empty, Text is a number, Text matches a pattern, Text starts with

## Text

### Change case

`ooa.string/change_case` (action)

Convert text to upper, lower, title or sentence case, or to camelCase, PascalCase, snake_case or kebab-case for names and keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Case | `case` | Text | Yes |  | Main | Target case: camel is invoiceTotal, pascal InvoiceTotal, snake invoice_total, kebab invoice-total. One of: `upper`, `lower`, `title`, `sentence`, `camel`, `pascal`, `snake`, `kebab` |

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

### Count occurrences

`ooa.string/count_occurrences` (action)

Count how many times a piece of text appears in a text (matches do not overlap).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to search in. |
| Find | `find` | Text | Yes |  | Main | Text to count. Example: `,` |
| Match case | `match_case` | Yes/No | No | Yes | Main | Count only matches with the same upper and lower case. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Count only matches that are whole words, not parts of longer words (cat in cat food, not in catalogue). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of times it appears; 0 when it does not. |

*Time limit 60 s; safe to repeat.*

### Count words and lines

`ooa.string/count_words` (action)

How many words, lines, sentences and characters a text has.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to count. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `words` | Number | Words (runs of letters and digits). |
| `lines` | Number | Lines, empty ones included; 0 for empty text. |
| `sentences` | Number | Sentences ending in . ! or ?. |
| `characters` | Number | Characters, spaces included. |
| `characters_no_spaces` | Number | Characters without spaces and line breaks. |

*Time limit 60 s; safe to repeat.*

### Encode or decode text

`ooa.string/encode` (action)

Make text safe for, or read it back from, a web address (URL), HTML, XML, JSON or Base64.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to change. |
| Direction | `direction` | Text | No | `encode` | Main | Encode or decode. One of: `encode`, `decode` |
| Format | `format` | Text | No | `url` | Main | URL: a value in a web address (a space is %20); HTML and XML: < > & and quotes; JSON: the inside of a JSON text (quotes, \n); Base64: UTF-8 bytes. One of: `url`, `html`, `xml`, `json`, `base64` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The encoded or decoded text. |

*Time limit 60 s; safe to repeat.*

### Escape for regex

`ooa.string/escape_regex` (action)

Put a backslash before every character that has a meaning in regular expressions, so the text is matched exactly, e.g. 1+1=2? to 1\+1=2\?.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to escape. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The text, safe to use inside a regular expression. |

*Time limit 60 s; safe to repeat.*

### Extract with pattern

`ooa.string/extract_pattern` (action)

Take the parts of a text a pattern describes, e.g. the date, number and amount of "Invoice 42 of 2026-11-16: 99.50"; named parts come back as a Dictionary.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to read. |
| Pattern | `pattern` | Text | Yes |  | Main | A regular expression with bracket groups, named like (?<total>[\d.]+) if you want them by name. Example: `Invoice (?<number>\d+) of (?<date>[\d-]+)` |
| Occurrence | `occurrence` | Number | No | `1` | Main | Which match to take: 1 for the first. Range: 1 to  |
| Ignore case | `ignore_case` | Yes/No | No | No | More options | Upper and lower case alike. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when the pattern was found. |
| `match` | Text | The whole match. |
| `groups` | List | Each bracket group, in order. |
| `named` | Dictionary | Each named group (?<name>...) by its name. |

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

### Fill a template

`ooa.string/fill_template` (action)

Put values into a text with {name} places, e.g. "Dear {first_name}, invoice {number} is due on {due}." from a Dictionary or a table row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | Text | Yes |  | Main | Text with places in curly brackets, e.g. {name}; {{ and }} write a bracket. |
| Values | `values` | Dictionary | Yes |  | Main | The values by name: a Dictionary or a table row (a Record). |
| If missing | `if_missing` | Text | No | `keep` | More options | A place without a value: keep {name}, leave it empty, or fail with STRING.MISSING_VALUE. One of: `keep`, `empty`, `error` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The filled text. |
| `missing` | List | Places that had no value. |

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

### Find all matches

`ooa.string/find_all` (action)

Every place a text or pattern occurs, as a list: all invoice numbers, e-mail addresses or amounts in a text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to search. |
| Find | `find` | Text | Yes |  | Main | Text or pattern to find. Example: `INV-\d+` |
| Use a pattern | `regex` | Yes/No | No | Yes | Main | Find is a regular expression. |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Upper and lower case alike. |
| Group | `group` | Number | No | `0` | More options | Keep this bracket group of each match; 0 keeps the whole match. Range: 0 to ; Shown when: `{"regex": ["true"]}` |
| Unique | `unique` | Yes/No | No | No | More options | Keep each match once. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `matches` | List | The matches, in order. |
| `count` | Number | How many. |

*Time limit 60 s; safe to repeat.*

### Insert text

`ooa.string/insert` (action)

Put text into another text at a position (counted from 0), at the start or at the end.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to change. |
| Text to insert | `insert` | Text | Yes |  | Main | What goes in. |
| Position | `position` | Number | No | `0` | Main | Where it goes, counted from 0; -1 puts it at the end. Range: -1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The text with the insert. |

*Time limit 60 s; safe to repeat.*

### Join texts

`ooa.string/join` (action)

Join the items of a list into one text with a separator, e.g. names into Ann, Bo and Cy, leaving out empty items if you like.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Items | `items` | List | Yes |  | Main | The texts, as a List variable or ["a", "b"]; numbers and dates are written as text. |
| Separator | `separator` | Text | No | `, ` | Main | Put between items; a line break is \n. |
| Skip empty | `skip_empty` | Yes/No | No | Yes | More options | Leave out empty items. |
| Last separator | `last_separator` | Text | No |  | More options | Put before the last item instead, e.g. " and ". |
| Prefix | `prefix` | Text | No |  | More options | Text before the result. |
| Suffix | `suffix` | Text | No |  | More options | Text after the result. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The joined text. |

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

### Mask text

`ooa.string/mask` (action)

Hide most of a text behind a character, keeping the first or last few, e.g. a card number as ************1111 or an e-mail as a***@example.com.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to mask. |
| Keep last | `keep_last` | Number | No | `4` | Main | Characters to keep visible at the end. Range: 0 to  |
| Keep first | `keep_first` | Number | No | `0` | Main | Characters to keep visible at the start. Range: 0 to  |
| Mask character | `mask_character` | Text | No | `*` | More options | The character that hides the rest. |
| Keep separators | `keep_separators` | Yes/No | No | Yes | More options | Leave spaces, dashes and dots as they are (4111 1111 1111 1111 becomes **** **** **** 1111). |
| It is an e-mail address | `email` | Yes/No | No | No | More options | Mask only the part before the @, keeping its first character. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The masked text. |

*Time limit 60 s; safe to repeat.*

### Normalize spaces

`ooa.string/normalize_spaces` (action)

Turn runs of spaces, tabs and no-break spaces into one space and trim the ends, keeping or joining the lines, e.g. for text read from web pages or PDFs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to tidy. |
| Keep line breaks | `keep_line_breaks` | Yes/No | No | No | Main | Keep the lines (tidying each, and dropping empty ones); when off, everything becomes one line. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The tidied text. |

*Time limit 60 s; safe to repeat.*

### Pad text

`ooa.string/pad` (action)

Fill a text up to a length with a character on the left or the right, e.g. 42 to 00042; longer text is left as it is.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text; a number is used as it is written, e.g. 42. |
| Length | `length` | Number | Yes |  | Main | Length the result should have, in characters. Example: `5`; Range: 0 to 100000 |
| Character | `character` | Text | No | ` ` | Main | One character to fill with; a space when empty. Example: `0` |
| Side | `side` | Text | No | `left` | Main | left puts the fill before the text (numbers, codes); right puts it after (fixed-width columns). One of: `left`, `right` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The padded text. |

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

### Remove characters

`ooa.string/remove_characters` (action)

Strip digits, letters, punctuation, spaces, line breaks or accents (é becomes e), or characters you list, e.g. to clean phone numbers or IDs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to clean. |
| Digits | `digits` | Yes/No | No | No | Main | Remove 0-9. |
| Letters | `letters` | Yes/No | No | No | Main | Remove letters of any alphabet. |
| Punctuation | `punctuation` | Yes/No | No | No | Main | Remove punctuation and symbols such as . , - ( ) / # @. |
| Spaces | `spaces` | Yes/No | No | No | Main | Remove spaces and tabs. |
| Line breaks | `line_breaks` | Yes/No | No | No | Main | Remove line breaks. |
| Accents (é to e) | `accents` | Yes/No | No | No | Main | Keep the letters but drop their accents: café becomes cafe, Müller becomes Muller. |
| Characters | `characters` | Text | No |  | More options | Any other characters to remove, typed one after another, e.g. $€£. |
| Keep only these instead | `keep_instead` | Yes/No | No | No | More options | Keep only the ticked kinds and listed characters, removing everything else (e.g. tick Digits to keep only digits). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The cleaned text. |

*Time limit 60 s; safe to repeat.*

### Remove part of text

`ooa.string/remove_part` (action)

Cut out a number of characters from a position (counted from 0), e.g. a prefix or a check digit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to change. |
| Start | `start` | Number | No | `0` | Main | First character to remove, counted from 0. Range: 0 to  |
| Length | `length` | Number | No |  | Main | How many characters; to the end when empty. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The text without that part. |

*Time limit 60 s; safe to repeat.*

### Repeat text

`ooa.string/repeat` (action)

Repeat a text a number of times, with a separator between, e.g. a line of dashes or placeholders.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to repeat. |
| Times | `times` | Number | No | `2` | Main | How many times. Range: 0 to 100000 |
| Separator | `separator` | Text | No |  | More options | Put between the copies. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The repeated text. |

*Time limit 60 s; safe to repeat.*

### Replace

`ooa.string/replace` (action)

Replace text, optionally using a regular expression.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Find | `find` | Text | Yes |  | Main | Text or regex to find. |
| Replace with | `replace_with` | Text | No | (empty) | Main | Replacement text. |
| Ignore case | `ignore_case` | Yes/No | No | No | More options | Match without regard to upper/lower case. |
| Find is a regular expression | `regex` | Yes/No | No | No | More options | Treat 'find' as a regular expression. |
| Start | `start` | Number | No | `0` | More options | Zero-based position to start replacing from; text before it is kept as is. Range: 0 to  |
| Count | `count` | Number | No | `-1` | More options | Replace at most this many matches; -1 replaces all. Range: -1 to  |

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

### Shorten text

`ooa.string/shorten` (action)

Cut a text to a maximum length and add an ending such as …, if you like at a word break, e.g. for subjects, columns or messages.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to shorten. |
| Maximum length | `max_length` | Number | No | `100` | Main | Longest result, ending included. Range: 1 to  |
| Ending | `ending` | Text | No | `…` | More options | Added when the text is cut; empty adds nothing. |
| Whole words | `whole_words` | Yes/No | No | Yes | More options | Cut at the last space rather than in a word. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | The shortened text (unchanged when it was short enough). |
| `shortened` | Yes/No | True when the text was cut. |

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
| Text | `text` | Text | Yes |  | Main | Text such as 1,234.56, 1.234,56, $ 1,200.50, (1,234.00) or 1,234.00- (negative). Example: `1.234,56` |
| Locale | `locale` | Text | No | `en-US` | More options | Locale the text is written in, e.g. en-US, de-DE, fr-FR. |
| Decimal separator | `decimal_separator` | Text | No |  | More options | Decimal separator; overrides the locale. |
| Grouping separator | `grouping_separator` | Text | No |  | More options | Thousands separator; overrides the locale. |
| Ignore currency | `ignore_currency` | Yes/No | No | Yes | More options | Ignore a currency symbol or code before or after the number ($, €, ₹, USD, EUR, Rs.); when off such text is not a number. |

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

### Text contains

`ooa.string/contains` (condition)

True when the text has the other text in it, anywhere or as a whole word.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
| Contains | `find` | Text | Yes |  | Main | The text to look for. |
| Ignore case | `ignore_case` | Yes/No | No | Yes | Main | Upper and lower case alike. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Only as a whole word (urgent, not urgently). |

### Text ends with

`ooa.string/ends_with` (condition)

True when the text finishes with the other text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
| Ends with | `end` | Text | Yes |  | Main | The ending to look for, e.g. .pdf. |
| Ignore case | `ignore_case` | Yes/No | No | Yes | Main | Upper and lower case alike. |

### Text is an email address

`ooa.string/is_email` (condition)

True when the text looks like an email address.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |

### Text is empty

`ooa.string/is_empty` (condition)

True when the text has no characters (or, if you like, only spaces).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | No |  | Main | Text to check; an empty variable counts as empty. |
| Only spaces counts as empty | `spaces_are_empty` | Yes/No | No | Yes | Main | Text of only spaces, tabs and line breaks counts as empty. |

### Text is a number

`ooa.string/is_number` (condition)

True when the text is a number, e.g. 1,250.50 or -3.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |

### Text matches a pattern

`ooa.string/matches_pattern` (condition)

True when the text fits a regular expression, as a whole or somewhere in it, e.g. ^INV-\d{6}$ for an invoice number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
| Pattern | `pattern` | Text | Yes |  | Main | The regular expression. Example: `^INV-\d{6}$` |
| Whole text | `whole_text` | Yes/No | No | Yes | Main | The whole text must fit; when off, a match anywhere counts. |
| Ignore case | `ignore_case` | Yes/No | No | No | Main | Upper and lower case alike. |

### Text starts with

`ooa.string/starts_with` (condition)

True when the text begins with the other text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
| Starts with | `start` | Text | Yes |  | Main | The beginning to look for. |
| Ignore case | `ignore_case` | Yes/No | No | Yes | Main | Upper and lower case alike. |
