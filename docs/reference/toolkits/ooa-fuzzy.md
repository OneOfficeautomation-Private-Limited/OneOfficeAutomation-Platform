# Fuzzy Match

Toolkit `ooa.fuzzy`, version 1.0.0. Score how alike two texts are, pick the closest candidate from a list and join tables on near-matching values.

[All toolkits](README.md)

## Contents

- **Fuzzy match**: Find closest match, Fuzzy join tables, Text similarity
- **Conditions**: Texts are similar

## Fuzzy match

### Find closest match

`ooa.fuzzy/best_match` (action)

Compare a text with every entry of a list and return the closest entry when it scores at least the threshold.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | No |  | Main; choice Text to look for = Typed text | Text to look for (or use text_file). |
| Text file | `text_file` | File path | No |  | Main; choice Text to look for = Text in a file | Text file whose content to look for, instead of text; spaces and line breaks at both ends are removed. |
| Candidates | `candidates` | List | Yes |  | Main | Entries to compare with, e.g. a list of customer names. |
| Threshold | `threshold` | Number | No | `80` | Main | Lowest score (0 to 100) accepted as a match. Range: 0 to 100 |
| Algorithm | `algorithm` | Text | No | `levenshtein` | More options | levenshtein: edit distance, good for typos. jaro_winkler: favours a shared start, good for short names. token_sort: ignores word order. token_set: ignores word order and extra words. One of: `levenshtein`, `jaro_winkler`, `token_sort`, `token_set` |
| Case sensitive | `case_sensitive` | Yes/No | No | No | More options | Treat upper and lower case as different. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when an entry reached the threshold. |
| `match` | Text | Closest entry, empty when none reached the threshold. |
| `score` | Number | Score of the closest entry (0 to 100), even when below the threshold. |
| `index` | Number | Zero-based position of the match in the list, -1 when none. |

*Time limit 60 s; safe to repeat.*

### Fuzzy join tables

`ooa.fuzzy/fuzzy_lookup` (action)

For each row of the first table, find the row of the second table whose column value is closest and append its columns and the score.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Left table | `left_table` | Table | Yes |  | Main | Table whose rows you want to enrich. |
| Left column | `left_column` | Text | Yes |  | Main | Column of the first table to match on. |
| Right table | `right_table` | Table | Yes |  | Main | Reference table to look values up in. |
| Right column | `right_column` | Text | Yes |  | Main | Column of the reference table to match on. |
| Threshold | `threshold` | Number | No | `80` | Main | Lowest score (0 to 100) accepted as a match. Range: 0 to 100 |
| Algorithm | `algorithm` | Text | No | `token_set` | More options | levenshtein: edit distance, good for typos. jaro_winkler: favours a shared start, good for short names. token_sort: ignores word order. token_set: ignores word order and extra words. One of: `levenshtein`, `jaro_winkler`, `token_sort`, `token_set` |
| Keep unmatched | `keep_unmatched` | Yes/No | No | Yes | More options | Keep rows without a match, with empty reference columns. Off: drop them. |
| Case sensitive | `case_sensitive` | Yes/No | No | No | More options | Treat upper and lower case as different. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | First table's columns, then the second table's (renamed with a right_ prefix on a clash), then match_score. |
| `matched` | Number | Rows that found a match. |
| `unmatched` | Number | Rows of the first table without a match. |

*Time limit 60 s; safe to repeat.*

### Text similarity

`ooa.fuzzy/similarity` (action)

Score how alike two texts are, from 0 (nothing in common) to 100 (identical).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text1 | `text1` | Text | Yes |  | Main | First text. |
| Text2 | `text2` | Text | Yes |  | Main | Second text. |
| Algorithm | `algorithm` | Text | No | `levenshtein` | More options | levenshtein: edit distance, good for typos. jaro_winkler: favours a shared start, good for short names. token_sort: ignores word order. token_set: ignores word order and extra words. One of: `levenshtein`, `jaro_winkler`, `token_sort`, `token_set` |
| Case sensitive | `case_sensitive` | Yes/No | No | No | More options | Treat upper and lower case as different. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `score` | Number | Similarity from 0 to 100. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Texts are similar

`ooa.fuzzy/texts_similar` (condition)

True when two texts score at least the threshold (0 to 100).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text1 | `text1` | Text | Yes |  | Main | First text. |
| Text2 | `text2` | Text | Yes |  | Main | Second text. |
| Threshold | `threshold` | Number | No | `80` | Main | Lowest score that counts as similar. Range: 0 to 100 |
| Algorithm | `algorithm` | Text | No | `levenshtein` | Main | levenshtein: edit distance, good for typos. jaro_winkler: favours a shared start, good for short names. token_sort: ignores word order. token_set: ignores word order and extra words. One of: `levenshtein`, `jaro_winkler`, `token_sort`, `token_set` |
| Case sensitive | `case_sensitive` | Yes/No | No | No | Main | Treat upper and lower case as different. |
