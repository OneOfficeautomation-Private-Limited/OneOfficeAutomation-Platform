# Data store

Toolkit `ooa.data`, version 1.0.0. Create, change, read, query and delete records in One CommandCenter's entities; upload, download and list files in storage buckets.

[All toolkits](README.md)

## Contents

- **Records**: Create record, Delete record, Get record, Query records, Update record
- **Storage buckets**: Delete file from bucket, Download file from bucket, List files in bucket, Upload file to bucket

## Records

### Create record

`ooa.data/create_record` (action)

Add a record to an entity; the fields are checked against the entity (types, required, unique).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Entity | `entity` | Text | Yes |  | Main | The entity's name, as in Manage > Data store. Example: `Suppliers` |
| Values | `values` | Dictionary | Yes |  | Main | Field to value. Example: `{"name": "ACME", "country": "IN"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Text | The new record's id. |
| `record` | Dictionary | The record as saved: id and its fields. |

*Time limit 60 s; needs: network.*

### Delete record

`ooa.data/delete_record` (action)

Delete a record; deleting one that is not there changes nothing and says so.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Entity | `entity` | Text | Yes |  | Main | The entity's name. |
| ID | `id` | Text | Yes |  | Main | The record's id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | Yes/No | True when the record was there. |

*Time limit 60 s; needs: network.*

### Get record

`ooa.data/get_record` (action)

Read one record by its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Entity | `entity` | Text | Yes |  | Main | The entity's name. |
| ID | `id` | Text | Yes |  | Main | The record's id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `record` | Dictionary | id and the record's fields. |

*Time limit 60 s; safe to repeat; needs: network.*

### Query records

`ooa.data/query_records` (action)

The records matching every filter, sorted, as a table (and as a list of dictionaries).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Entity | `entity` | Text | Yes |  | Main | The entity's name. |
| Filters | `filters` | List | No |  | Main | Each {field, op, value}; op is =, !=, >, >=, <, <=, contains, starts_with, empty or not_empty. Text compares without case. Example: `[{"field": "country", "op": "=", "value": "IN"}]` |
| Sort by | `sort_by` | Text | No |  | More options | A field to sort by. |
| Descending | `descending` | Yes/No | No | No | More options | Largest or latest first. |
| Limit | `limit` | Number | No | `1000` | More options | At most this many records. Range: 1 to 5000 |
| Offset | `offset` | Number | No | `0` | More options | Skip this many first (for pages). Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | id and every field, one row per record. |
| `records` | List | The records as dictionaries. |
| `total` | Number | How many records match (all pages). |

*Time limit 60 s; safe to repeat; needs: network.*

### Update record

`ooa.data/update_record` (action)

Change some fields of a record; fields not given keep their values, and an empty value clears a field.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Entity | `entity` | Text | Yes |  | Main | The entity's name. |
| ID | `id` | Text | Yes |  | Main | The record's id. |
| Values | `values` | Dictionary | Yes |  | Main | Field to new value. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `record` | Dictionary | The record as saved. |

*Time limit 60 s; needs: network.*

## Storage buckets

### Delete file from bucket

`ooa.data/delete_file` (action)

Delete a file from a storage bucket.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bucket | `bucket` | Text | Yes |  | Main | The bucket's name. |
| Path | `path` | Text | Yes |  | Main | Its path in the bucket. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | Yes/No | True when the file was there. |

*Time limit 60 s; needs: network.*

### Download file from bucket

`ooa.data/download_file` (action)

Save a file from a storage bucket in the run's folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bucket | `bucket` | Text | Yes |  | Main | The bucket's name. |
| Path | `path` | Text | Yes |  | Main | Its path in the bucket. |
| Save as | `save_as` | File path | No |  | Main | Where to save it (empty: its name, in the run's folder). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file` | File path | Where it was saved. |

*Time limit 60 s; needs: network, filesystem.*

### List files in bucket

`ooa.data/list_files` (action)

The bucket's files, optionally only those whose path starts with a prefix.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bucket | `bucket` | Text | Yes |  | Main | The bucket's name. |
| Prefix | `prefix` | Text | No |  | More options | Only paths starting with this, such as invoices/2026/. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | Table | path, bytes, modified. |
| `paths` | List | The paths only. |

*Time limit 60 s; safe to repeat; needs: network.*

### Upload file to bucket

`ooa.data/upload_file` (action)

Put a file from the run's folder into a storage bucket, at a path such as invoices/2026/acme.pdf.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | Yes |  | Main | The file to upload. |
| Bucket | `bucket` | Text | Yes |  | Main | The bucket's name. |
| Path | `path` | Text | No |  | Main | Its path in the bucket (empty: the file's name). |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a file already at that path. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | Text | Its path in the bucket. |
| `bytes` | Number | Its size. |

*Time limit 60 s; needs: network, filesystem.*
