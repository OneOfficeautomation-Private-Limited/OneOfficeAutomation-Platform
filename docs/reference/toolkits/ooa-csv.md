# CSV/TXT

Toolkit `ooa.csv`, version 1.0.0. Read and write delimited text files as tables.

[All toolkits](README.md)

## Contents

- **CSV**: Close, Open, Read to table, Write from table
- **Loop types**: Each row in CSV/TXT

## CSV

### Close

`ooa.csv/close` (action)

Close a CSV/TXT session opened with Open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `csv` | Main | Session name. |

*Time limit 60 s; safe to repeat.*

### Open

`ooa.csv/open` (action)

Open a CSV or delimited text file as a named session, read once, for Read and Each row steps.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Session | `session` | Text | No | `csv` | Main | Session name for later steps. |
| First row is the header | `header` | Yes/No | No | Yes | Main | First row holds column names. |
| Delimiter | `delimiter` | Text | No | `,` | Main | Field delimiter: , ; \| tab, newline (one value per line), or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,). |
| Trim | `trim` | Text | No | `none` | Main | Remove spaces at the start and/or end of every value. One of: `none`, `leading`, `trailing`, `both` |
| Encoding | `encoding` | Text | No | `auto` | Main | Text encoding: auto (the file's own: a byte order mark, else UTF-8 when it fits, else windows-1252), utf-8, utf-8-bom, ansi, unicode, utf-16, windows-1251 ... |
| Skip lines | `skip_lines` | Number | No | `0` | More options | Lines to skip at the top of the file, before the header. Range: 0 to  |
| Skip empty rows | `skip_empty_rows` | Yes/No | No | No | More options | Leave out rows whose values are all empty (blank lines are always skipped). |
| Detect numbers and dates | `detect_types` | Yes/No | No | No | More options | Turn values that are clearly numbers, true/false or ISO dates into typed values; codes with leading zeros stay text. |
| Agents this one runs may use it | `share` | Yes/No | No | No | More options | Agents started from this one with Run agent may use this session by its name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `row_count` | Number | Number of data rows. |
| `columns` | List | Column names. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Read to table

`ooa.csv/read` (action)

Read a CSV or delimited text file, or an open CSV/TXT session, into a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Read from = A file | File path (or use an open session). |
| First row is the header | `header` | Yes/No | No | Yes | Main; choice Read from = A file | First row holds column names. |
| Delimiter | `delimiter` | Text | No | `,` | Main; choice Read from = A file | Field delimiter: , ; \| tab, newline (one value per line), or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,). |
| Trim | `trim` | Text | No | `none` | Main; choice Read from = A file | Remove spaces at the start and/or end of every value. One of: `none`, `leading`, `trailing`, `both` |
| Encoding | `encoding` | Text | No | `utf-8` | Main; choice Read from = A file | Text encoding: auto (the file's own: a byte order mark, else UTF-8 when it fits, else windows-1252), utf-8, utf-8-bom, ansi, unicode, utf-16, windows-1251 ... |
| Session | `session` | Text | No |  | Main; choice Read from = An open session | Read the table of this open CSV/TXT session instead of a file (the reading options are those of its Open step). |
| Skip lines | `skip_lines` | Number | No | `0` | More options | Lines to skip at the top of the file, before the header. Range: 0 to  |
| Maximum rows | `max_rows` | Number | No |  | More options; tick Read only the first rows | Read at most this many data rows; all when empty. Range: 0 to  |
| Skip empty rows | `skip_empty_rows` | Yes/No | No | No | More options | Leave out rows whose values are all empty (blank lines are always skipped). |
| Detect numbers and dates | `detect_types` | Yes/No | No | No | More options | Turn values that are clearly numbers, true/false or ISO dates (2026-10-23) into typed values; codes with leading zeros stay text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table of text values (typed values with detect_types). |
| `row_count` | Number | Number of data rows. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Write from table

`ooa.csv/write` (action)

Write a table to a CSV file, overwriting or appending rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table to write. |
| Path | `path` | File path | Yes |  | Main | Target path. |
| First row is the header | `header` | Yes/No | No | Yes | Main | Write column names first (not when appending to an existing file). |
| Delimiter | `delimiter` | Text | No | `,` | Main | Field delimiter: , ; \| tab, or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,). |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding: utf-8, utf-8-bom, ansi, unicode, utf-16 ... |
| Append | `append` | Yes/No | No | No | Main | Append rows to an existing file. |
| Quote all | `quote_all` | Yes/No | No | No | More options | Put every value in double quotes, not only values that need them. |
| Line ending | `line_ending` | Text | No | `crlf` | More options | Line ending: crlf (Windows) or lf. One of: `crlf`, `lf` |

*Time limit 60 s; needs: filesystem.*

## Loop types

Used as the kind of loop in a Loop step.

### Each row in CSV/TXT

`ooa.csv/rows` (loop type)

Yield each data row of a CSV or delimited text file, or of an open CSV/TXT session, as a record keyed by column name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main | File path (or use an open session). |
| Delimiter | `delimiter` | Text | No | `,` | Main | Field delimiter: , ; \| tab, newline (one value per line), or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,). |
| First row is the header | `header` | Yes/No | No | Yes | Main | First row holds column names. |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding; auto reads the file's own. |
| Trim | `trim` | Text | No | `none` | Main | Remove spaces at the start and/or end of every value. One of: `none`, `leading`, `trailing`, `both` |
| Detect numbers and dates | `detect_types` | Yes/No | No | No | Main | Turn values that are clearly numbers, true/false or ISO dates into typed values. |
| Session | `session` | Text | No |  | Main | Go through the rows of this open CSV/TXT session instead of a file. |

*Needs: filesystem.*
