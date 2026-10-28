# Data Table

Toolkit `ooa.datatable`, version 1.0.0. Build, filter, sort, search and change in-memory tables.

[All toolkits](README.md)

## Contents

- **Table**: Add column from list, Add row, Group and summarise, Change column type, Clear rows, Column count, Column name, Column names, Delete column, Delete rows, Filter rows, Find value, Create from records, Get value, Insert column, Insert row, Join tables, Look up value, Merge tables, Remove duplicate rows, Rename column, Row count, Set value, Sort, Sum column, Convert to records, Write to file
- **Conditions**: Column count is, Column contains value, Table has column, Table is empty, Row count is

## Table

### Add column from list

`ooa.datatable/add_column_from_list` (action)

Add a column at the start, the end or a zero-based position, filled with the items of a list row by row; a longer list adds rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table to add the column to. |
| List | `list` | List | Yes |  | Main | Items for the new column: the first item goes in the first row, and so on. Rows beyond the list stay empty. |
| Name | `name` | Text | Yes |  | Main | New column name. |
| Position | `position` | Number | No |  | Main; choice Insert at = A position | Zero-based column position (0 = first); at the end when empty. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Add row

`ooa.datatable/add_row` (action)

Append a row: a Dictionary of column values, or a List of values in column order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Values | `values` | Any | Yes |  | Main | Column name to value (Dictionary), or the values in column order (List). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Group and summarise

`ooa.datatable/aggregate` (action)

Group rows by one or more columns and compute count, sum, average, minimum, maximum, first or last per group; without group columns, one summary row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Group by | `group_by` | List | No |  | Main | Columns to group by, e.g. ["Vendor"]; one summary row for the whole table when empty. |
| Summaries | `summaries` | Dictionary | Yes |  | Main | New column name to 'function:column', e.g. {"Total": "sum:Amount", "Invoices": "count"}. Functions: count, sum, avg, min, max, first, last, count_distinct. Example: `{"Total": "sum:Amount", "Invoices": "count"}` |
| Sort groups | `sort_groups` | Yes/No | No | Yes | More options | Order the groups by their group values; in order of first appearance when off. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per group: the group columns, then one column per summary. |
| `count` | Number | Number of groups. |

*Time limit 60 s; safe to repeat.*

### Change column type

`ooa.datatable/change_column_type` (action)

Convert every value in a column to String, Number, DateTime or Boolean; empty cells stay empty.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | No |  | Main; choice Column = By name | Column to convert. |
| Column index | `column_index` | Number | No |  | Main; choice Column = By position | Zero-based column index; used when 'column' is empty. Range: 0 to  |
| Type | `type` | Text | Yes |  | Main | Target type. One of: `String`, `Number`, `DateTime`, `Boolean` |
| Date format | `date_format` | Text | No |  | Main | Pattern of the text when converting to DateTime, e.g. dd/MM/yyyy; ISO dates when empty. Shown when: `{"type": ["DateTime"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Clear rows

`ooa.datatable/clear` (action)

Remove every row but keep the columns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Empty table with the same columns. |

*Time limit 60 s; safe to repeat.*

### Column count

`ooa.datatable/column_count` (action)

Number of columns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Non empty only | `non_empty_only` | Yes/No | No | No | Main | Count only columns with at least one value. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of columns. |

*Time limit 60 s; safe to repeat.*

### Column name

`ooa.datatable/column_name` (action)

Return the name of the column at a zero-based index.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column index | `column_index` | Number | Yes |  | Main | Zero-based column index. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Column name. |

*Time limit 60 s; safe to repeat.*

### Column names

`ooa.datatable/column_names` (action)

List the column names in order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `names` | List | Column names. |

*Time limit 60 s; safe to repeat.*

### Delete column

`ooa.datatable/delete_column` (action)

Remove a column and its values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | No |  | Main; choice Column to delete = By name | Column to remove. |
| Column index | `column_index` | Number | No |  | Main; choice Column to delete = By position | Zero-based column index; used when 'column' is empty. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Delete rows

`ooa.datatable/delete_row` (action)

Delete the row at a position, or every row where a column equals a value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Row | `row` | Number | No |  | Main; choice Rows to delete = The row at a position | Zero-based row to delete. Range: 0 to  |
| Column | `column` | Text | No |  | Main; choice Rows to delete = Rows where a column matches | Column to match (used when row is empty). |
| Value | `value` | Any | No |  | Main; choice Rows to delete = Rows where a column matches | Delete rows whose column equals this value. Shown when: `{"column": ["!", ""]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |
| `count` | Number | Rows deleted. |

*Time limit 60 s; safe to repeat.*

### Filter rows

`ooa.datatable/filter` (action)

Keep rows where a column satisfies a comparison.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | Yes |  | Main | Column name. |
| Operator | `operator` | Text | Yes |  | Main | Comparison; matches takes a regular expression. One of: `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `contains`, `not_contains`, `starts_with`, `ends_with`, `matches`, `empty`, `not_empty` |
| Value | `value` | Any | No |  | Main | Value to compare with (not needed for empty/not_empty). Shown when: `{"operator": ["!", "empty", "not_empty"]}` |
| Match case | `match_case` | Yes/No | No | No | Main | Compare text with upper/lower case taken into account. Shown when: `{"operator": ["eq", "ne", "contains", "not_contains", "starts_with", "ends_with", "matches"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Filtered table. |
| `count` | Number | Rows kept. |

*Time limit 60 s; safe to repeat.*

### Find value

`ooa.datatable/find` (action)

Return the zero-based numbers of rows containing a value, in one column or in any column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Value | `value` | Any | Yes |  | Main | Value to look for. |
| Match case | `match_case` | Yes/No | No | No | Main | Compare text with upper/lower case taken into account. |
| Column | `column` | Text | No |  | More options | Search only this column; all columns when empty. |
| Match | `match` | Text | No | `equals` | More options | equals (numbers compare by value) or contains (text). One of: `equals`, `contains` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows` | List | Zero-based row numbers that match. |
| `count` | Number | Number of matching rows. |
| `found` | Yes/No | True when at least one row matches. |
| `cells` | List | Every matching cell as a dictionary with zero-based row and column numbers and the column name. |
| `first_row` | Number | Zero-based number of the first matching row, or -1. |

*Time limit 60 s; safe to repeat.*

### Create from records

`ooa.datatable/from_records` (action)

Build a table from a list of dictionaries.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Records | `records` | List | Yes |  | Main | List of dictionaries. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | New table. |

*Time limit 60 s; safe to repeat.*

### Get value

`ooa.datatable/get_value` (action)

Read one cell by zero-based row number and column name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Row | `row` | Number | Yes |  | Main | Zero-based row number. Range: 0 to  |
| Column | `column` | Text | No |  | Main; choice Column = By name | Column name. |
| Column index | `column_index` | Number | No |  | Main; choice Column = By position | Zero-based column index; used when 'column' is empty. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Cell value. |

*Time limit 60 s; safe to repeat.*

### Insert column

`ooa.datatable/insert_column` (action)

Add a column at the end or at a zero-based position, filled with a default value or with the values of a column of another table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Position | `position` | Number | No |  | Main; choice Insert at = A position | Zero-based column position; at the end when empty. Range: 0 to  |
| Name | `name` | Text | Yes |  | Main | New column name. |
| Default value | `default_value` | Any | No |  | Main; choice Fill with = A default value | Value for every existing row; empty when not set. |
| Source table | `source_table` | Table | No |  | Main; choice Fill with = A column of another table | Table whose column is copied into the new column, row by row; rows it does not have get the default value. |
| Source column | `source_column` | Text | No |  | Main; choice Fill with = A column of another table | Name of the column of source_table to copy. |
| Source column index | `source_column_index` | Number | No |  | Main; choice Fill with = A column of another table | Zero-based index of the column of source_table to copy; used when source_column is empty. Range: 0 to  |
| Column type | `column_type` | Text | No |  | More options | Convert the new column's values to this type; kept as they are when empty. One of: `String`, `Number`, `DateTime`, `Boolean` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Insert row

`ooa.datatable/insert_row` (action)

Insert a row, given as a dictionary of column values, at a zero-based position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Row | `row` | Number | No |  | Main; choice Insert at = A position | Zero-based position for the new row; at the end when empty. Range: 0 to  |
| Values | `values` | Dictionary | No |  | Main | Column name to value; missing columns stay empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Join tables

`ooa.datatable/join` (action)

Combine two tables on matching key columns; inner keeps matches only, left keeps every row of the first table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | First (left) table. |
| Key columns | `key_columns` | List | Yes |  | Main | Columns present in both tables that must match. |
| Other | `other` | Table | Yes |  | Main | Second (right) table. |
| Other key columns | `other_key_columns` | List | No |  | Main | Key columns of the second table when their names differ, in the same order as key_columns. |
| Join type | `join_type` | Text | No | `inner` | Main | inner keeps matches only; left keeps every row of the first table, right every row of the second, full every row of both. One of: `inner`, `left`, `right`, `full` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Joined table: first table columns, then the second table's other columns. |
| `count` | Number | Rows in the result. |

*Time limit 60 s; safe to repeat.*

### Look up value

`ooa.datatable/lookup` (action)

Find the first row whose column holds a value and return another column of that row, or a fallback value when no row matches.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table to look in. |
| Look in column | `column` | Text | Yes |  | Main | Column that holds the value to look for. Example: `Order` |
| Value to look for | `value` | Any | Yes |  | Main | Numbers compare by value; text by equality. |
| Return the value of column | `return_column` | Text | Yes |  | Main | Column whose value is returned from the matching row. Example: `Status` |
| Value when not found | `if_not_found` | Any | No |  | Main | Returned when no row matches; empty when not set. |
| Match case | `match_case` | Yes/No | No | No | More options | Compare text with upper/lower case taken into account. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | The value from the return column, or the fallback when nothing matches. |
| `found` | Yes/No | True when a row matches. |
| `row` | Number | Zero-based number of the matching row, or -1. |

*Time limit 60 s; safe to repeat.*

### Merge tables

`ooa.datatable/merge` (action)

Stack the rows of several tables; columns are matched by name, using the union of all columns or requiring the same ones.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Tables | `tables` | List | Yes |  | Main | Tables to merge, in order. |
| Columns | `columns` | Text | No | `union` | More options | union adds missing columns as empty; same fails when columns differ. One of: `union`, `same` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Merged table. |

*Time limit 60 s; safe to repeat.*

### Remove duplicate rows

`ooa.datatable/remove_duplicates` (action)

Keep the first row for each distinct value of the chosen columns (all columns when empty).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Columns | `columns` | List | No |  | Main | Columns that define a duplicate. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table without duplicates. |

*Time limit 60 s; safe to repeat.*

### Rename column

`ooa.datatable/rename_column` (action)

Give a column a new name; its values stay as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | No |  | Main; choice Column = By name | Current column name. |
| Column index | `column_index` | Number | No |  | Main; choice Column = By position | Zero-based column index; used when 'column' is empty. Range: 0 to  |
| New name | `new_name` | Text | Yes |  | Main | New column name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Row count

`ooa.datatable/row_count` (action)

Number of rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Non empty only | `non_empty_only` | Yes/No | No | No | Main | Count only rows with at least one value; rows whose cells are all empty are skipped. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of rows. |

*Time limit 60 s; safe to repeat.*

### Set value

`ooa.datatable/set_value` (action)

Change one cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Row | `row` | Number | Yes |  | Main | Zero-based row number. Range: 0 to  |
| Column | `column` | Text | No |  | Main; choice Column = By name | Column name. |
| Column index | `column_index` | Number | No |  | Main; choice Column = By position | Zero-based column index; used when 'column' is empty. Range: 0 to  |
| Value | `value` | Any | No |  | Main | New value. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Updated table. |

*Time limit 60 s; safe to repeat.*

### Sort

`ooa.datatable/sort` (action)

Sort rows by a column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | No |  | Main; choice Sort by column = Name | Column name. |
| Column index | `column_index` | Number | No |  | Main; choice Sort by column = Position | Zero-based column index; used when 'column' is empty. Range: 0 to  |
| Descending | `descending` | Yes/No | No | No | Main | Largest first. |
| Ignore case | `ignore_case` | Yes/No | No | Yes | More options | Sort text without regard to upper/lower case. |
| Then by | `then_by` | List | No |  | More options | Further column names that order rows with equal values, in the same direction. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Sorted table. |

*Time limit 60 s; safe to repeat.*

### Sum column

`ooa.datatable/sum_column` (action)

Add up the numeric values in a column; empty cells are ignored.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |
| Column | `column` | Text | Yes |  | Main | Column name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `total` | Number | Sum. |

*Time limit 60 s; safe to repeat.*

### Convert to records

`ooa.datatable/to_records` (action)

Turn each row into a dictionary keyed by column name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Source table. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `records` | List | List of dictionaries. |

*Time limit 60 s; safe to repeat.*

### Write to file

`ooa.datatable/write_to_file` (action)

Save a table as a CSV file or as a JSON list of records, replacing the file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table to save. |
| Path | `path` | File path | Yes |  | Main | Target file, e.g. output/report.csv. |
| Append | `append` | Yes/No | No | No | Main | Add the rows to the end of an existing CSV file (no header is repeated). Shown when: `{"format": ["csv"]}` |
| Row delimiter | `row_delimiter` | Text | No |  | Main | Text written after each CSV row instead of the line ending, e.g. \| or ;\n (\n, \r and \t are understood). Example: `|`; Shown when: `{"format": ["csv"]}` |
| Delimiter | `delimiter` | Text | No | `,` | Main | CSV delimiter (one character). Shown when: `{"format": ["csv"]}` |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding, e.g. utf-8, utf-8-bom, ansi (windows-1252), utf-16. |
| Format | `format` | Text | No | `csv` | More options | csv or json. One of: `csv`, `json` |
| First row is the header | `header` | Yes/No | No | Yes | More options | Write the column names as the first CSV line. Shown when: `{"format": ["csv"]}` |
| Line ending | `line_ending` | Text | No | `crlf` | More options | Line ending of CSV rows. One of: `crlf`, `lf`; Shown when: `{"format": ["csv"]}` |
| Create folders | `create_folders` | Yes/No | No | Yes | More options | Create the file's folder when it does not exist; when off, a missing folder is an error. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the written file. |

*Time limit 60 s; needs: filesystem.*

## Conditions

Used in If, Loop while and Wait until steps.

### Column count is

`ooa.datatable/column_count_is` (condition)

Compare the number of columns with a number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table. |
| Operator | `operator` | Text | No | `eq` | Main | Comparison. One of: `eq`, `ne`, `gt`, `ge`, `lt`, `le` |
| Count | `count` | Number | Yes |  | Main | Number to compare with. |

### Column contains value

`ooa.datatable/contains_value` (condition)

True when any row has this value in the column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table. |
| Column | `column` | Text | Yes |  | Main | Column name. |
| Value | `value` | Any | Yes |  | Main | Value to look for. |

### Table has column

`ooa.datatable/has_column` (condition)

True when the table has a column with this name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table. |
| Column | `column` | Text | Yes |  | Main | Column name. |

### Table is empty

`ooa.datatable/is_empty` (condition)

True when the table has no rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table. |

### Row count is

`ooa.datatable/row_count_is` (condition)

Compare the number of rows with a number, e.g. more than 0 rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table. |
| Operator | `operator` | Text | No | `eq` | Main | Comparison. One of: `eq`, `ne`, `gt`, `ge`, `lt`, `le` |
| Count | `count` | Number | Yes |  | Main | Number to compare with. |
