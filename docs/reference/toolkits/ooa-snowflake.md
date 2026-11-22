# Snowflake

Toolkit `ooa.snowflake`, version 1.0.0. Run SQL in Snowflake, read results into data tables, browse databases, schemas and tables, and load rows.

[All toolkits](README.md)

## Contents

- **SQL**: Run stored procedure, Cancel statement, Run SQL statement, Export results to file, Get statement results, Get statement status
- **Connection**: Connect, Disconnect
- **Loading**: Delete rows by key, Insert rows from data table, Load file into table, Update rows by key
- **Catalog**: List columns, List databases, List schemas, List tables, List warehouses

## SQL

### Run stored procedure

`ooa.snowflake/call_procedure` (action)

Call a stored procedure with values for its arguments, sent apart from the SQL, and return what it gives back (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Procedure | `procedure` | Text | Yes |  | Main | Procedure name, written as DATABASE.SCHEMA.NAME (or just NAME). Example: `SALES.PUBLIC.CLOSE_MONTH` |
| Parameters | `parameters` | Any | No |  | Main | Argument values: a list in the procedure's order, or a dictionary of argument name to value. Example: `{"MONTH": "2026-10"}` |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database of the table when table_name has no database. |
| Schema | `schema` | Text | No |  | More options | Schema of the table when table_name has no schema. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Wait (seconds) | `wait_seconds` | Number | No | `3000` | More options | How long to wait for the procedure to finish. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The value the procedure returned (the first cell of its result). |
| `table` | Table | The whole result, for a procedure that returns a table. |
| `statement_handle` | Text | Id of the statement. |

*Time limit 3600 s; needs: network.*

### Cancel statement

`ooa.snowflake/cancel_statement` (action)

Stop a statement that is still running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Statement handle | `statement_handle` | Text | Yes |  | Main | Handle from Run SQL statement. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

*Time limit 60 s; needs: network.*

### Run SQL statement

`ooa.snowflake/execute_statement` (action)

Run one SQL statement and return its rows as a data table. Use ? in the SQL for values passed in bindings.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| SQL statement | `sql` | Text | Yes |  | Main | SQL to run. Put ? where each binding value goes. Example: `SELECT * FROM ORDERS WHERE REGION = ? AND AMOUNT > ?` |
| Bindings | `bindings` | List | No |  | Main; tick Fill in ? placeholders | Values for the ? placeholders, in order, e.g. ["EMEA", 1000]. |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database to use for names that are not fully written out. |
| Schema | `schema` | Text | No |  | More options | Schema to use for names that are not fully written out. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Wait (seconds) | `wait_seconds` | Number | No | `600` | More options | How long to wait for the statement to finish before returning with status running. |
| Maximum rows | `max_rows` | Number | No | `100000` | More options | Most rows to bring back into the table. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Result rows (empty for statements that return no rows). |
| `row_count` | Number | Number of rows in the table. |
| `rows_affected` | Number | Rows inserted, updated or deleted by a data change. |
| `statement_handle` | Text | Id of the statement, for status, results or cancel. |
| `status` | Text | succeeded, or running when it did not finish within the wait time. |

*Time limit 3600 s; needs: network.*

### Export results to file

`ooa.snowflake/export_results` (action)

Run one SQL statement and write all of its rows to a file (CSV, tab-separated, JSON lines or Parquet), optionally compressed, without holding them all in memory.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| SQL statement | `sql` | Text | Yes |  | Main | SQL to run. Put ? where each binding value goes. Example: `SELECT * FROM ORDERS WHERE ORDER_DATE >= ?` |
| Bindings | `bindings` | List | No |  | Main; tick Fill in ? placeholders | Values for the ? placeholders, in order, e.g. ["2026-10-01"]. |
| File | `file` | File path | Yes |  | Main | File to write; add .gz yourself when compressing. Example: `exports/orders.csv` |
| Format | `format` | Text | No | `csv` | Main | csv (comma-separated, quoted where needed), tsv (tab-separated), json_lines (one JSON object per row) or parquet (columnar, typed; G6). One of: `csv`, `tsv`, `json_lines`, `parquet` |
| First row is the header | `header` | Yes/No | No | Yes | Main | Write the column names as the first line. Shown when: `{"format": ["csv", "tsv"]}` |
| Compression | `compression` | Text | No | `none` | Main | none, or gzip to compress the file. For parquet: auto or snappy (snappy inside the file), gzip, or none. Auto on the other formats compresses with gzip when the file name ends with .gz (G6). One of: `none`, `gzip`, `auto`, `snappy` |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace a file that already exists. |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database to use for names that are not fully written out. |
| Schema | `schema` | Text | No |  | More options | Schema to use for names that are not fully written out. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Wait (seconds) | `wait_seconds` | Number | No | `3000` | More options | How long to wait for the statement to finish. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file` | File path | The file written. |
| `row_count` | Number | Rows written (not counting the header). |
| `statement_handle` | Text | Id of the statement. |

*Time limit 3600 s; needs: network,filesystem, filesystem.*

### Get statement results

`ooa.snowflake/fetch_results` (action)

Read the rows of a finished statement into a data table, page by page.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Statement handle | `statement_handle` | Text | Yes |  | Main | Handle from Run SQL statement. |
| Maximum rows | `max_rows` | Number | No | `100000` | More options | Most rows to bring back. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Result rows. |
| `row_count` | Number | Number of rows in the table. |
| `total_rows` | Number | Rows in the full result, which can be more than max_rows. |

*Time limit 3600 s; safe to repeat; needs: network.*

### Get statement status

`ooa.snowflake/get_statement_status` (action)

Check whether a statement that was left running has finished, failed or is still going.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Statement handle | `statement_handle` | Text | Yes |  | Main | Handle from Run SQL statement. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | running, succeeded or failed. |
| `message` | Text | Snowflake's message, including the error text when it failed. |
| `total_rows` | Number | Rows in the result once it succeeded. |

*Time limit 60 s; safe to repeat; needs: network.*

## Connection

### Connect

`ooa.snowflake/connect` (action)

Keep the address and sign-in under a session for the steps after it, so they need not repeat them (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | The account url the later steps use (G6). |
| Token | `token` | Credential (vault) | No |  | Main | The token the later steps use (G6). |
| Token type | `token_type` | Text | No |  | Main | The token type the later steps use (G6). |
| Session | `session` | Text | No |  | More options | The session's name; the toolkit's default session when empty. |
| Share | `share` | Yes/No | No | No | More options | Agents this one runs use the same session. |

*Time limit 60 s.*

### Disconnect

`ooa.snowflake/disconnect` (action)

Forget the open Snowflake session; later steps then need their own address and sign-in (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No |  | Main | The session; the toolkit's default session when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `closed` | Yes/No | True when a session was open. |

*Time limit 60 s.*

## Loading

### Delete rows by key

`ooa.snowflake/delete_rows` (action)

Delete the rows of a table whose key column has one of the values given; the values are sent apart from the SQL (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Table name | `table_name` | Text | Yes |  | Main | Table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set). Example: `SALES.PUBLIC.ORDERS` |
| Key column | `key_column` | Text | Yes |  | Main | Column that identifies a row. Example: `ORDER_ID` |
| Key values | `key_values` | List | Yes |  | Main | Key values of the rows to delete. Example: `[1001, 1002]` |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database of the table when table_name has no database. |
| Schema | `schema` | Text | No |  | More options | Schema of the table when table_name has no schema. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_changed` | Number | Rows deleted. |

*Time limit 3600 s; needs: network.*

### Insert rows from data table

`ooa.snowflake/insert_rows` (action)

Add every row of a data table to a Snowflake table. Columns are matched by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Table name | `table_name` | Text | Yes |  | Main | Target table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set). Example: `SALES.PUBLIC.ORDERS` |
| Data | `data` | Table | Yes |  | Main | Rows to add. Its column names must match columns of the target table. |
| Batch size | `batch_size` | Number | No | `500` | More options | Rows sent per request. |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database of the table when table_name has no database. |
| Schema | `schema` | Text | No |  | More options | Schema of the table when table_name has no schema. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_inserted` | Number | Number of rows added. |

*Time limit 3600 s; needs: network.*

### Load file into table

`ooa.snowflake/load_file` (action)

Load a CSV or Parquet file into a table: a file on this machine is first put on the table's stage or a named stage, a file already on a stage is used as it is; then Snowflake copies it in (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Table name | `table_name` | Text | Yes |  | Main | Table to load into, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set). Example: `SALES.PUBLIC.ORDERS` |
| File | `file` | File path | Yes |  | Main | A CSV or Parquet file on this machine, or a path on a stage such as @MY_STAGE/orders/ (then nothing is uploaded). Example: `exports/orders.csv` |
| Stage | `stage` | Text | No |  | Main | Named stage to put a local file on; the table's own stage when empty. The uploaded copy is removed once it is loaded. Example: `SALES.PUBLIC.INBOX` |
| Format | `format` | Text | No | `csv` | Main | csv (comma-separated, values may be in double quotes) or parquet (columns matched to the table's by name). One of: `csv`, `parquet` |
| Skip header | `skip_header` | Number | No | `1` | Main; tick Skip header lines | Lines at the top of a CSV file that are not data, such as the column names. Range: 0 to ; Shown when: `{"format": ["csv"]}` |
| On error | `on_error` | Text | No | `abort` | Main | On a bad row: abort (load nothing), continue (load the good rows), skip_file (leave out the file), or skip the file once it has on_error_limit bad rows or that percent of bad rows. One of: `abort`, `continue`, `skip_file`, `skip_file_after_rows`, `skip_file_after_percent` |
| On error limit | `on_error_limit` | Number | No | `1` | Main | The number of bad rows, or the percent of bad rows, after which a file is skipped. Range: 1 to ; Shown when: `{"on_error": ["skip_file_after_rows", "skip_file_after_percent"]}` |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database of the table when table_name has no database. |
| Schema | `schema` | Text | No |  | More options | Schema of the table when table_name has no schema. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Wait (seconds) | `wait_seconds` | Number | No | `3000` | More options | How long to wait for each statement to finish. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_loaded` | Number | Rows added to the table. |
| `errors_seen` | Number | Bad rows Snowflake found (skipped ones included). |
| `results` | Table | Snowflake's result: one row per file with its status, rows loaded and first error. |
| `statement_handle` | Text | Id of the copy statement. |

*Time limit 7200 s; needs: network, filesystem.*

### Update rows by key

`ooa.snowflake/update_rows` (action)

Set columns to new values in the rows of a table whose key column has one of the values given; the values are sent apart from the SQL (G6).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Table name | `table_name` | Text | Yes |  | Main | Table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set). Example: `SALES.PUBLIC.ORDERS` |
| Key column | `key_column` | Text | Yes |  | Main | Column that identifies a row. Example: `ORDER_ID` |
| Key values | `key_values` | List | Yes |  | Main | Key values of the rows to update. Example: `[1001, 1002]` |
| Values | `values` | Dictionary | Yes |  | Main | Column to its new value. Example: `{"STATUS": "SHIPPED", "SHIPPED_ON": "2026-11-22"}` |
| Warehouse | `warehouse` | Text | No |  | More options | Warehouse that runs the query. Leave empty for the user's default warehouse. |
| Database | `database` | Text | No |  | More options | Database of the table when table_name has no database. |
| Schema | `schema` | Text | No |  | More options | Schema of the table when table_name has no schema. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_changed` | Number | Rows updated. |

*Time limit 3600 s; needs: network.*

## Catalog

### List columns

`ooa.snowflake/list_columns` (action)

Return the columns of a table with their data types.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Table name | `table_name` | Text | Yes |  | Main | Table, written as DATABASE.SCHEMA.TABLE. Example: `SALES.PUBLIC.ORDERS` |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per column with name, type, nullability and default. |
| `names` | List | Column names only. |

*Time limit 600 s; safe to repeat; needs: network.*

### List databases

`ooa.snowflake/list_databases` (action)

Return the databases the role can see.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per database with name, owner, comment and more. |
| `names` | List | Database names only. |

*Time limit 600 s; safe to repeat; needs: network.*

### List schemas

`ooa.snowflake/list_schemas` (action)

Return the schemas in a database.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Database | `database` | Text | Yes |  | Main | Database to look in. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per schema. |
| `names` | List | Schema names only. |

*Time limit 600 s; safe to repeat; needs: network.*

### List tables

`ooa.snowflake/list_tables` (action)

Return the tables in a schema.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Database | `database` | Text | Yes |  | Main | Database to look in. |
| Schema | `schema` | Text | Yes |  | Main | Schema to look in, e.g. PUBLIC. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per table with name, row count, size and more. |
| `names` | List | Table names only. |

*Time limit 600 s; safe to repeat; needs: network.*

### List warehouses

`ooa.snowflake/list_warehouses` (action)

Return the warehouses the role can use, with their size and state.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | No |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | No |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per warehouse. |
| `names` | List | Warehouse names only. |

*Time limit 600 s; safe to repeat; needs: network.*
