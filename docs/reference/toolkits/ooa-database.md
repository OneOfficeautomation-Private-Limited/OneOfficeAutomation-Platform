# Database

Toolkit `ooa.database`, version 1.0.0. Connect to databases and run parameterised queries, updates and transactions.

[All toolkits](README.md)

## Contents

- **Transactions**: Begin transaction, Commit, Roll back
- **Database**: Bulk insert, Run stored procedure, Connect, Disconnect, Insert/update/delete, Export to CSV file, Read from (query)
- **Schema**: Describe table, List tables
- **Loop types**: Each row of a SQL query

## Transactions

### Begin transaction

`ooa.database/begin_transaction` (action)

Start a transaction; changes are kept only after Commit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `db` | Main | Session name. |

*Time limit 60 s.*

### Commit

`ooa.database/commit` (action)

Save the changes made since Begin transaction.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `db` | Main | Session name. |

*Time limit 60 s.*

### Roll back

`ooa.database/rollback` (action)

Undo the changes made since Begin transaction.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `db` | Main | Session name. |

*Time limit 60 s.*

## Database

### Bulk insert

`ooa.database/bulk_insert` (action)

Insert every row of a Data Table, or of a delimited text file read line by line, into a database table in batches, all or nothing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | Yes |  | Main | Database table to insert into. Example: `dbo.invoices` |
| Data | `data` | Table | No |  | Main; choice Rows from = A Data Table | Data Table whose rows are inserted (or use source_file). |
| Source file | `source_file` | File path | No |  | Main; choice Rows from = A delimited file | Delimited text file (CSV) whose rows are inserted instead of a Data Table; it is read as it goes, so large files fit. Empty fields are inserted as NULL. Example: `input/invoices.csv` |
| Delimiter | `delimiter` | Text | No | `,` | Main; choice Rows from = A delimited file | Field separator of the file: one character, or tab. Shown when: `{"source_file": ["!", ""]}` |
| Start row | `start_row` | Number | No | `1` | Main; choice Rows from = A delimited file | Line of the file where reading starts (1 = the first line); lines above it, such as a title, are skipped. Range: 1 to ; Shown when: `{"source_file": ["!", ""]}` |
| Has header | `has_header` | Yes/No | No | Yes | Main; choice Rows from = A delimited file | The first line read holds the column names, used as database columns when columns is empty. Shown when: `{"source_file": ["!", ""]}` |
| Encoding | `encoding` | Text | No | `utf-8` | Main; choice Rows from = A delimited file | Text encoding of the file, e.g. utf-8 or windows-1252. Shown when: `{"source_file": ["!", ""]}` |
| Columns | `columns` | List | No |  | Main | Database column for each Data Table or file column, in order; the Data Table's column names (or the file's header) when empty. |
| Batch size | `batch_size` | Number | No | `500` | Main | Rows sent to the database per batch. Range: 1 to 100000 |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_inserted` | Number | Rows inserted. |

*Time limit 3600 s; needs: network.*

### Run stored procedure

`ooa.database/call_procedure` (action)

Call a stored procedure or function with input, output and in-out parameters; return the rows it produces, its output values and a function's return value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Procedure | `procedure` | Text | Yes |  | Main | Procedure name, optionally schema.name. Example: `dbo.close_period` |
| Parameters | `parameters` | List | No |  | Main | Parameters in the procedure's order: a plain value is an input; {"value": v, "direction": "out" or "inout", "type": "varchar\|integer\|bigint\|decimal\|double\|boolean\|date\|timestamp", "name": "p_total"} declares an output. |
| Returns value | `returns_value` | Yes/No | No | No | Main; tick It is a function that returns a value | Call it as a function ({? = call ...}) and return its value as out_parameters.return. |
| Return type | `return_type` | Text | No |  | Main; tick It is a function that returns a value | SQL type of the function's value; varchar when empty. One of: `varchar`, `integer`, `bigint`, `decimal`, `double`, `boolean`, `date`, `timestamp` |
| Maximum rows | `max_rows` | Number | No | `100000` | Main | Safety limit on rows returned. Range: 1 to  |
| Query timeout (seconds) | `query_timeout_seconds` | Number | No | `0` | Main | Cancel the statement after this many seconds; 0 waits as long as the step allows. Range: 0 to  |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | First result set, or an empty table. |
| `row_count` | Number | Rows in the result table. |
| `rows_affected` | Number | Rows changed, when the database reports it; otherwise -1. |
| `out_parameters` | Dictionary | Output and in-out values by parameter name (or position 1, 2 ... when unnamed); the function's value as "return". |

*Time limit 600 s; needs: network.*

### Connect

`ooa.database/connect` (action)

Open a database connection as a named session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Database type | `database_type` | Text | No |  | Main; choice Connect using = Connection details | Kind of database, used to build the URL when jdbc_url is empty. One of: `sqlserver`, `postgresql`, `mysql`, `oracle`, `sqlite`, `h2`, `access` |
| Server | `server` | Text | No |  | Main; choice Connect using = Connection details | Database server name or address. Example: `dbserver01`; Shown when: `{"database_type": ["sqlserver", "postgresql", "mysql", "oracle"]}` |
| Port | `port` | Number | No |  | Main; choice Connect using = Connection details | Server port; the database's usual port when empty. Range: 1 to 65535; Shown when: `{"database_type": ["sqlserver", "postgresql", "mysql", "oracle"]}` |
| Database | `database` | Text | No |  | Main; choice Connect using = Connection details | Database name (service name for Oracle). Shown when: `{"database_type": ["sqlserver", "postgresql", "mysql", "oracle"]}` |
| File | `file` | File path | No |  | Main; choice Connect using = Connection details | Database file for SQLite, H2 or Access. Shown when: `{"database_type": ["sqlite", "h2", "access"]}` |
| Database address (JDBC URL) | `jdbc_url` | Text | No |  | Main; choice Connect using = A JDBC address | JDBC URL, e.g. jdbc:sqlserver://host;databaseName=erp; when empty it is built from database_type and the fields below. |
| User | `user` | Text | No |  | Main | User name. |
| Password | `password` | Credential (vault) | No |  | Main | Password from the vault. |
| Session | `session` | Text | No | `db` | More options | Session name for later steps. |
| Driver class | `driver_class` | Text | No |  | More options; tick Load a specific driver class | JDBC driver class to load first, for drivers that do not register themselves. Example: `org.postgresql.Driver` |
| Login timeout (seconds) | `login_timeout_seconds` | Number | No | `0` | More options | Give up connecting after this many seconds; 0 uses the driver's default. Range: 0 to  |
| Properties | `properties` | Dictionary | No |  | More options | Extra driver properties, e.g. {"encrypt": "true"}; Credential values are revealed only to the driver. |
| Read only | `read_only` | Yes/No | No | No | More options | Open the connection read-only, so the agent cannot change data by mistake (when the driver supports it). |
| Auto commit | `auto_commit` | Yes/No | No | Yes | More options | Save each statement at once; false starts in a transaction that needs Commit. |

*Time limit 60 s; needs: network.*

### Disconnect

`ooa.database/disconnect` (action)

Close the session. Uncommitted changes are rolled back.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `db` | Main | Session name. |

*Time limit 60 s.*

### Insert/update/delete

`ooa.database/execute` (action)

Run an INSERT, UPDATE, DELETE or DDL statement with ? parameters.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| SQL statement | `sql` | Text | Yes |  | Main | Statement with ? placeholders. |
| Parameters | `parameters` | List | No |  | Main | Values for the ? placeholders, in order. |
| Query timeout (seconds) | `query_timeout_seconds` | Number | No | `0` | Main | Cancel the statement after this many seconds; 0 waits as long as the step allows. Range: 0 to  |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows_affected` | Number | Rows changed. |

*Time limit 60 s; needs: network.*

### Export to CSV file

`ooa.database/export_to_file` (action)

Run a SELECT or a stored procedure with ? parameters and write its rows to a CSV file without loading them into memory.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| SQL statement | `sql` | Text | No |  | Main; choice Rows from = A SELECT | SELECT statement with ? placeholders (or use procedure). |
| Procedure | `procedure` | Text | No |  | Main; choice Rows from = A stored procedure | Stored procedure whose first result set is written, used instead of sql; parameters are its input values in order. Example: `dbo.month_report` |
| Parameters | `parameters` | List | No |  | Main | Values for the ? placeholders, in order. |
| Path | `path` | File path | Yes |  | Main | CSV file to create. |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding of the file. |
| First row is the header | `header` | Yes/No | No | Yes | Main | Write the column names as the first line. |
| Delimiter | `delimiter` | Text | No | `,` | More options | Field separator, e.g. , or ; or tab. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing file; when false an existing file stops the step. |
| Maximum rows | `max_rows` | Number | No | `0` | More options | Stop after writing this many rows; 0 writes them all. Range: 0 to  |
| Query timeout (seconds) | `query_timeout_seconds` | Number | No | `0` | More options | Cancel the statement after this many seconds; 0 waits as long as the step allows. Range: 0 to  |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Written file. |
| `row_count` | Number | Rows written. |

*Time limit 3600 s; needs: network, filesystem.*

### Read from (query)

`ooa.database/query` (action)

Run a SELECT with ? parameters and return the rows as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| SQL statement | `sql` | Text | Yes |  | Main | SELECT statement with ? placeholders. |
| Parameters | `parameters` | List | No |  | Main | Values for the ? placeholders, in order. |
| Maximum rows | `max_rows` | Number | No | `100000` | Main | Safety limit on rows returned. Range: 1 to  |
| Query timeout (seconds) | `query_timeout_seconds` | Number | No | `0` | Main | Cancel the statement after this many seconds; 0 waits as long as the step allows. Range: 0 to  |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Result rows. |
| `row_count` | Number | Number of rows. |

*Time limit 60 s; needs: network.*

## Schema

### Describe table

`ooa.database/describe_table` (action)

Return the columns of a table with their types, sizes and whether they accept empty values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | Yes |  | Main | Table name, optionally schema.table. |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: name, type, size, nullable, position. |

*Time limit 60 s; safe to repeat; needs: network.*

### List tables

`ooa.database/list_tables` (action)

List the tables and views the connection can see, optionally in one schema.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Schema | `schema` | Text | No |  | Main; tick Only one schema | Only this schema; all schemas when empty. |
| Include views | `include_views` | Yes/No | No | Yes | Main | Also list views. |
| Session | `session` | Text | No | `db` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: schema, name, type. |

*Time limit 60 s; safe to repeat; needs: network.*

## Loop types

Used as the kind of loop in a Loop step.

### Each row of a SQL query

`ooa.database/query_rows` (loop type)

Run a SELECT with ? parameters and yield each row as a record keyed by column name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| SQL statement | `sql` | Text | Yes |  | Main | SELECT statement with ? placeholders. |
| Parameters | `parameters` | List | No |  | Main | Values for the ? placeholders, in order. |
| Maximum rows | `max_rows` | Number | No | `100000` | Main | Safety limit on rows read. Range: 1 to  |
| Session | `session` | Text | No | `db` | Main | Session name. |
| Query timeout (seconds) | `query_timeout_seconds` | Number | No | `0` | Main | Cancel the statement after this many seconds; 0 waits as long as the step allows. Range: 0 to  |
