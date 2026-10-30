# ServiceNow

Toolkit `ooa.servicenow`, version 1.0.0. Query, create and update ServiceNow records (incidents, requests, any table), add comments and attachments.

[All toolkits](README.md)

## Contents

- **Records**: Add comment or work note, Create record, Delete record, Get record, List changed records, Query records, Update record
- **Attachments**: Attach file, Delete attachment, Download attachment, List attachments
- **Authentication**: Authenticate, Revoke authentication

## Records

### Add comment or work note

`ooa.servicenow/add_comment` (action)

Add a customer-visible comment or an internal work note.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |
| Text | `text` | Text | Yes |  | Main | Comment text. |
| Internal | `internal` | Yes/No | No | No | Main | Internal work note instead of a visible comment. |

*Time limit 60 s; needs: network.*

### Create record

`ooa.servicenow/create_record` (action)

Create a record, e.g. an incident; returns its sys_id and number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. Example: `incident` |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to value, e.g. {"short_description": "...", "urgency": "2"}. |
| Input display value | `input_display_value` | Yes/No | No | No | More options | Values are display values (e.g. a user's name for caller_id) that the instance should look up. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sys_id` | Text | New sys_id. |
| `number` | Text | Record number, e.g. INC0012345. |
| `record` | Dictionary | The new record as the instance returned it. |

*Time limit 60 s; needs: network.*

### Delete record

`ooa.servicenow/delete_record` (action)

Delete a record by sys_id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |

*Time limit 60 s; needs: network.*

### Get record

`ooa.servicenow/get_record` (action)

Read one record by sys_id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |
| Fields | `fields` | List | No |  | Main | Fields to return; all when empty. |
| Display value | `display_value` | Text | No | `true` | More options | true: display values (e.g. user names); false: stored values (e.g. sys_ids); all: both, as field and dv_field. One of: `true`, `false`, `all` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `record` | Dictionary | Field values. |

*Time limit 60 s; safe to repeat; needs: network.*

### List changed records

`ooa.servicenow/list_changed_records` (action)

Return records created or updated after a point in time, oldest first, for polling-style triggers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. Example: `incident` |
| Since | `since` | Text | Yes |  | Main | UTC time; records updated strictly after it are returned. Example: `2026-10-19 08:00:00` |
| Query | `query` | Text | No |  | Main | Extra encoded query to narrow the records. Example: `active=true` |
| Fields | `fields` | List | No |  | Main | Fields to return; sys_id and sys_updated_on are always included. |
| Maximum records | `max_records` | Number | No | `1000` | More options | Maximum records. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Changed records (values as stored, times in UTC). |
| `count` | Number | Number of records. |
| `latest` | Text | Newest sys_updated_on seen; pass it as since on the next poll (equal to since when nothing changed). |

*Time limit 300 s; safe to repeat; needs: network.*

### Query records

`ooa.servicenow/query` (action)

Return records matching an encoded query (as copied from a ServiceNow list filter).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name (basic sign-in). |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault (instead of user and password). |
| Table | `table` | Text | Yes |  | Main | Table name. Example: `incident` |
| Query | `query` | Text | No |  | Main | Encoded query. Example: `active=true^priority=1` |
| Filter | `filter` | Dictionary | No |  | Main | Field to required value, all of which must match; combined with query when both are given. No query syntax needed. Example: `{"active": true, "priority": 1}` |
| Fields | `fields` | List | No |  | Main | Fields to return, e.g. ["number","short_description","state"]. |
| Maximum records | `max_records` | Number | No | `1000` | More options | Maximum records. |
| Display value | `display_value` | Text | No | `true` | More options | true: display values (e.g. user names); false: stored values (e.g. sys_ids); all: both, as field and dv_field. One of: `true`, `false`, `all` |
| Order by | `order_by` | Text | No |  | More options | Field to sort by; put - in front for newest or largest first, e.g. -sys_created_on. Example: `sys_created_on` |
| Offset | `offset` | Number | No | `0` | More options | Skip this many matching records first (for paging). |
| Exclude reference link | `exclude_reference_link` | Yes/No | No | Yes | More options | Leave out the API links of reference fields. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Records. |
| `count` | Number | Number of records. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update record

`ooa.servicenow/update_record` (action)

Change fields of a record.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to new value. |
| Input display value | `input_display_value` | Yes/No | No | No | More options | Values are display values that the instance should look up. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `record` | Dictionary | The record after the change, as the instance returned it. |

*Time limit 60 s; needs: network.*

## Attachments

### Attach file

`ooa.servicenow/attach_file` (action)

Attach a local file to a record.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |
| Path | `path` | File path | Yes |  | Main | File to attach. |
| File name | `file_name` | Text | No |  | More options | Name to give the attachment; the local file name when empty. |
| Content type | `content_type` | Text | No |  | More options | Content type; guessed from the file when empty. Example: `application/pdf` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attachment_id` | Text | Attachment sys_id. |

*Time limit 600 s; needs: network, filesystem.*

### Delete attachment

`ooa.servicenow/delete_attachment` (action)

Delete an attachment by its sys_id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Attachment ID | `attachment_id` | Text | Yes |  | Main | Attachment sys_id, from List attachments. |

*Time limit 60 s; needs: network.*

### Download attachment

`ooa.servicenow/download_attachment` (action)

Save an attachment to a local folder under its original file name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Attachment ID | `attachment_id` | Text | Yes |  | Main | Attachment sys_id, from List attachments. |
| Folder | `folder` | File path | No | `.` | Main | Folder to save into. |
| File name | `file_name` | Text | No |  | More options | Name to save the file under; the attachment's own name when empty. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a file of the same name; when off, the action stops with an error instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |

*Time limit 600 s; safe to repeat; needs: network, filesystem.*

### List attachments

`ooa.servicenow/list_attachments` (action)

List the files attached to a record.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| User | `user` | Text | No |  | Main; choice Sign in with = User name and password | User name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = User name and password | Password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth token from the vault. |
| Table | `table` | Text | Yes |  | Main | Table name. |
| Record sys_id | `sys_id` | Text | Yes |  | Main | Record sys_id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: sys_id, file_name, content_type, size_bytes, created_on. |
| `count` | Number | Number of attachments. |

*Time limit 60 s; safe to repeat; needs: network.*

## Authentication

### Authenticate

`ooa.servicenow/authenticate` (action)

Get an OAuth access token from the instance (user and password, or client credentials) for the other ServiceNow actions.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| Grant type | `grant_type` | Text | No | `password` | Main | password: sign in as a user; client_credentials: as the application. One of: `password`, `client_credentials` |
| Client ID | `client_id` | Text | Yes |  | Main | Client id of the OAuth application registry entry. |
| Client secret | `client_secret` | Credential (vault) | Yes |  | Main | Client secret from the vault. |
| User | `user` | Text | No |  | Main | User name (password grant). |
| Password | `password` | Credential (vault) | No |  | Main | Password from the vault (password grant). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `access_token` | Credential (vault) | Access token; map it to access_token of later actions. |
| `refresh_token` | Credential (vault) | Refresh token, when the instance returns one. |
| `expires_in` | Number | Seconds until the access token expires. |

*Time limit 60 s; needs: network.*

### Revoke authentication

`ooa.servicenow/revoke_authentication` (action)

Revoke an access or refresh token so it can no longer be used.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.service-now.com. |
| Client ID | `client_id` | Text | Yes |  | Main | Client id of the OAuth application. |
| Client secret | `client_secret` | Credential (vault) | Yes |  | Main | Client secret from the vault. |
| Token | `token` | Credential (vault) | Yes |  | Main | The token to revoke. |

*Time limit 60 s; needs: network.*
