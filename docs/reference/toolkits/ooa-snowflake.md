# Snowflake

Toolkit `ooa.snowflake`, version 1.0.0. Run SQL in Snowflake, read results into data tables, browse databases, schemas and tables, and load rows.

[All toolkits](README.md)

## Contents

- **SQL**: Cancel statement, Run SQL statement, Export results to file, Get statement results, Get statement status
- **Loading**: Insert rows from data table
- **Catalog**: List columns, List databases, List schemas, List tables, List warehouses

## SQL

### Cancel statement

`ooa.snowflake/cancel_statement` (action)

Stop a statement that is still running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
| Statement handle | `statement_handle` | Text | Yes |  | Main | Handle from Run SQL statement. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

*Time limit 60 s; needs: network.*

### Run SQL statement

`ooa.snowflake/execute_statement` (action)

Run one SQL statement and return its rows as a data table. Use ? in the SQL for values passed in bindings.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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

Run one SQL statement and write all of its rows to a file (CSV, tab-separated or JSON lines), optionally gzip-compressed, without holding them in memory.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
| SQL statement | `sql` | Text | Yes |  | Main | SQL to run. Put ? where each binding value goes. Example: `SELECT * FROM ORDERS WHERE ORDER_DATE >= ?` |
| Bindings | `bindings` | List | No |  | Main; tick Fill in ? placeholders | Values for the ? placeholders, in order, e.g. ["2026-10-01"]. |
| File | `file` | File path | Yes |  | Main | File to write; add .gz yourself when compressing. Example: `exports/orders.csv` |
| Format | `format` | Text | No | `csv` | Main | csv (comma-separated, quoted where needed), tsv (tab-separated) or json_lines (one JSON object per row). One of: `csv`, `tsv`, `json_lines` |
| First row is the header | `header` | Yes/No | No | Yes | Main | Write the column names as the first line. Shown when: `{"format": ["csv,tsv"]}` |
| Compression | `compression` | Text | No | `none` | Main | none, or gzip to compress the file. One of: `none`, `gzip` |
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

*Time limit 3600 s; needs: network,filesystem.*

### Get statement results

`ooa.snowflake/fetch_results` (action)

Read the rows of a finished statement into a data table, page by page.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
| Statement handle | `statement_handle` | Text | Yes |  | Main | Handle from Run SQL statement. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | running, succeeded or failed. |
| `message` | Text | Snowflake's message, including the error text when it failed. |
| `total_rows` | Number | Rows in the result once it succeeded. |

*Time limit 60 s; safe to repeat; needs: network.*

## Loading

### Insert rows from data table

`ooa.snowflake/insert_rows` (action)

Add every row of a data table to a Snowflake table. Columns are matched by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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

## Catalog

### List columns

`ooa.snowflake/list_columns` (action)

Return the columns of a table with their data types.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
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
| Account address (URL) | `account_url` | Text | Yes |  | Main | Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com. |
| Token | `token` | Credential (vault) | Yes |  | Main | Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. |
| Role | `role` | Text | No |  | More options | Role to run as. Leave empty for the user's default role. |
| Token type | `token_type` | Text | No | `OAUTH` | More options | Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN. One of: `OAUTH`, `KEYPAIR_JWT`, `PROGRAMMATIC_ACCESS_TOKEN` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per warehouse. |
| `names` | List | Warehouse names only. |

*Time limit 600 s; safe to repeat; needs: network.*
