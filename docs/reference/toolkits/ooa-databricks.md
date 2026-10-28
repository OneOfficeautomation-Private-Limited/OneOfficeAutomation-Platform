# Databricks

Toolkit `ooa.databricks`, version 1.0.0. Run SQL on a Databricks SQL warehouse, start and watch jobs, start or stop clusters, and move files in and out of the workspace.

[All toolkits](README.md)

## Contents

- **Jobs**: Cancel job run, Get job run, List jobs, Run job now
- **SQL**: Cancel statement, Run SQL statement, Get statement results, Get statement status
- **Files**: Delete file, Download file, Upload file
- **Clusters**: List clusters, Start cluster, Stop cluster

## Jobs

### Cancel job run

`ooa.databricks/cancel_run` (action)

Stop a job run that is queued or running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Run ID | `run_id` | Text | Yes |  | Main | Run id from Run job now. |

*Time limit 60 s; needs: network.*

### Get job run

`ooa.databricks/get_run` (action)

Check the progress and outcome of a job run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Run ID | `run_id` | Text | Yes |  | Main | Run id from Run job now. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `life_cycle_state` | Text | Where the run is, e.g. PENDING, RUNNING or TERMINATED. |
| `result_state` | Text | SUCCESS, FAILED, TIMEDOUT or CANCELED once finished. |
| `run` | Dictionary | run_id, life_cycle_state, result_state, state_message, run_page_url, start_time, end_time. |

*Time limit 60 s; safe to repeat; needs: network.*

### List jobs

`ooa.databricks/list_jobs` (action)

Return the jobs in the workspace, optionally only those with a given name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Maximum results | `max_results` | Number | No | `1000` | Main | Most jobs to return. |
| Name | `name` | Text | No |  | More options | Only the job with exactly this name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: job_id, name, creator, created_time. |
| `count` | Number | Number of jobs returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Run job now

`ooa.databricks/run_job` (action)

Start a job right away, optionally with parameters, and wait for it to finish if you like.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Job ID | `job_id` | Text | Yes |  | Main | Job to start (see List jobs). |
| Job parameters | `job_parameters` | Dictionary | No |  | Main | Job parameter values, e.g. {"run_date": "2026-10-20"}. |
| Wait until finished | `wait_until_finished` | Yes/No | No | No | More options | Wait for the run to end before going on. |
| Wait (seconds) | `wait_seconds` | Number | No | `3600` | More options | Longest time to wait when waiting is on. |
| Fail if run fails | `fail_if_run_fails` | Yes/No | No | Yes | More options | When waiting, stop the agent with an error if the run does not succeed. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `run_id` | Text | Id of the job run. |
| `life_cycle_state` | Text | Where the run is, e.g. PENDING, RUNNING or TERMINATED. |
| `result_state` | Text | SUCCESS, FAILED, TIMEDOUT or CANCELED once it has finished; empty before that. |
| `run_page_url` | Text | Link to the run in the Databricks workspace. |

*Time limit 86400 s; needs: network.*

## SQL

### Cancel statement

`ooa.databricks/cancel_statement` (action)

Stop a SQL statement that is still running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Statement ID | `statement_id` | Text | Yes |  | Main | Id from Run SQL statement. |

*Time limit 60 s; needs: network.*

### Run SQL statement

`ooa.databricks/execute_sql` (action)

Run SQL on a SQL warehouse and return the rows as a data table. Use :name in the SQL for values passed in parameters.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Warehouse ID | `warehouse_id` | Text | Yes |  | Main | SQL warehouse that runs the statement (from its Connection details). |
| SQL statement | `sql` | Text | Yes |  | Main | SQL to run. Example: `SELECT * FROM sales.orders WHERE region = :region` |
| Parameters | `parameters` | Dictionary | No |  | Main | Values for :name markers in the SQL, e.g. {"region": "EMEA"}. |
| Maximum rows | `max_rows` | Number | No | `100000` | Main | Most rows to bring back into the table. |
| Wait (seconds) | `wait_seconds` | Number | No | `600` | Main | How long to wait for the result before returning with status RUNNING. |
| Catalog | `catalog` | Text | No |  | More options | Catalog for names that are not fully written out. |
| Schema | `schema` | Text | No |  | More options | Schema for names that are not fully written out. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Result rows (empty for statements that return no rows). |
| `row_count` | Number | Number of rows in the table. |
| `statement_id` | Text | Id of the statement, for status, results or cancel. |
| `status` | Text | SUCCEEDED, or PENDING / RUNNING when it did not finish within the wait time. |

*Time limit 3600 s; needs: network.*

### Get statement results

`ooa.databricks/fetch_results` (action)

Read the rows of a finished SQL statement into a data table, chunk by chunk.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Statement ID | `statement_id` | Text | Yes |  | Main | Id from Run SQL statement. |
| Maximum rows | `max_rows` | Number | No | `100000` | Main | Most rows to bring back. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | How long to wait for a running statement to finish; 0 fails at once if it is still running. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Result rows. |
| `row_count` | Number | Number of rows in the table. |
| `total_rows` | Number | Rows in the full result, which can be more than max_rows. |

*Time limit 3600 s; safe to repeat; needs: network.*

### Get statement status

`ooa.databricks/get_statement_status` (action)

Check whether a SQL statement that was left running has finished.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Statement ID | `statement_id` | Text | Yes |  | Main | Id from Run SQL statement. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED or CLOSED. |
| `message` | Text | Error text when the statement failed. |
| `total_rows` | Number | Rows in the result once it succeeded. |

*Time limit 60 s; safe to repeat; needs: network.*

## Files

### Delete file

`ooa.databricks/delete_file` (action)

Delete a file from a Unity Catalog volume, the workspace, or DBFS (a DBFS folder with recursive).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | File to delete. Example: `/Volumes/main/finance/inbox/old.csv` |
| Recursive | `recursive` | Yes/No | No | No | More options | DBFS only: also delete a folder with everything in it. |

*Time limit 60 s; needs: network.*

### Download file

`ooa.databricks/download_file` (action)

Save a file from a Unity Catalog volume, the workspace, or DBFS to this machine.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | File to download, e.g. /Volumes/catalog/schema/volume/file.csv or dbfs:/FileStore/file.csv. Example: `/Volumes/main/finance/outbox/result.csv` |
| Save as | `save_as` | File path | Yes |  | Main | Where to save it on this machine. Missing folders are created. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a file already at save_as; when off, the action stops instead and nothing is downloaded. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the saved file. |
| `size` | Number | File size in bytes. |

*Time limit 3600 s; needs: network, filesystem.*

### Upload file

`ooa.databricks/upload_file` (action)

Copy a local file into a Unity Catalog volume, the workspace, or DBFS.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Local path | `local_path` | File path | Yes |  | Main | File on this machine to upload. |
| Remote path | `remote_path` | Text | Yes |  | Main | Destination, e.g. /Volumes/catalog/schema/volume/file.csv or dbfs:/FileStore/file.csv. Example: `/Volumes/main/finance/inbox/report.csv` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file that is already there. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `remote_path` | Text | Where the file was written. |

*Time limit 3600 s; needs: network, filesystem.*

## Clusters

### List clusters

`ooa.databricks/list_clusters` (action)

Return the workspace's clusters and their state.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: cluster_id, name, state, spark_version, node_type, creator. |
| `count` | Number | Number of clusters. |

*Time limit 60 s; safe to repeat; needs: network.*

### Start cluster

`ooa.databricks/start_cluster` (action)

Start a stopped cluster, and wait until it is running if you like. Nothing happens if it is already running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Cluster ID | `cluster_id` | Text | Yes |  | Main | Cluster to start (see List clusters). |
| Wait until running | `wait_until_running` | Yes/No | No | Yes | More options | Wait until the cluster is ready before going on. |
| Wait (seconds) | `wait_seconds` | Number | No | `1200` | More options | Longest time to wait. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `state` | Text | Cluster state afterwards, e.g. PENDING or RUNNING. |

*Time limit 3600 s; needs: network.*

### Stop cluster

`ooa.databricks/terminate_cluster` (action)

Stop a running cluster to save cost. Its settings are kept so it can be started again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workspace address (URL) | `workspace_url` | Text | Yes |  | Main | Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Databricks personal access token or OAuth token from the vault. |
| Cluster ID | `cluster_id` | Text | Yes |  | Main | Cluster to stop. |

*Time limit 60 s; needs: network.*
