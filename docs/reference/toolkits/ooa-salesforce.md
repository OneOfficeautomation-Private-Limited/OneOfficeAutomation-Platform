# Salesforce

Toolkit `ooa.salesforce`, version 1.0.0. Query Salesforce with SOQL and create, read, update and delete records.

[All toolkits](README.md)

## Contents

- **Authentication**: Authenticate, Revoke authentication
- **Records**: Create record, Delete record, Describe object, Find records, Get record, Query (SOQL), Create, update or upsert many records, Update record, Upsert by external id
- **Files**: Get attachments, Upload attachment

## Authentication

### Authenticate

`ooa.salesforce/authenticate` (action)

Get an access token from Salesforce with a connected app (client credentials, or user name and password) for the other Salesforce actions.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Grant type | `grant_type` | Text | No | `client_credentials` | Main | client_credentials: the connected app's run-as user; password: a named user with password and security token. One of: `client_credentials`, `password` |
| Client ID | `client_id` | Text | Yes |  | Main | Consumer key of the connected app. |
| Client secret | `client_secret` | Credential (vault) | Yes |  | Main | Consumer secret from the vault. |
| Username | `username` | Text | No |  | Main | User name (password grant only). |
| Password | `password` | Credential (vault) | No |  | Main | Password from the vault (password grant only). |
| Login address (URL) | `login_url` | Text | No | `https://login.salesforce.com` | Main | Login host: https://login.salesforce.com, https://test.salesforce.com for sandboxes, or your My Domain URL (required for client credentials). |
| Security token | `security_token` | Credential (vault) | No |  | More options | Security token, added after the password, when the user's IP is not trusted (password grant only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `access_token` | Credential (vault) | Access token; map it to access_token of later actions. |
| `instance_url` | Text | Instance URL returned by Salesforce; map it to instance_url of later actions. |

*Time limit 60 s; needs: network.*

### Revoke authentication

`ooa.salesforce/revoke_authentication` (action)

Revoke an access token so it can no longer be used.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | The token to revoke. |

*Time limit 60 s; needs: network.*

## Records

### Create record

`ooa.salesforce/create_record` (action)

Create a record and return its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Case` |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to value. |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Text | New record id. |

*Time limit 60 s; needs: network.*

### Delete record

`ooa.salesforce/delete_record` (action)

Delete a record (it goes to the Salesforce recycle bin).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. |
| ID | `id` | Text | Yes |  | Main | Record id. |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

*Time limit 60 s; needs: network.*

### Describe object

`ooa.salesforce/describe_object` (action)

Read what an object has: its fields with their labels, types, lengths, whether they are required, picklist values and lookups.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Case` |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Table | Columns: name, label, type, length, required, updateable, picklist_values, reference_to. |
| `object` | Dictionary | name, label, label_plural, key_prefix, createable, updateable, deletable, custom. |

*Time limit 60 s; safe to repeat; needs: network.*

### Find records

`ooa.salesforce/find_records` (action)

Find records of an object whose fields have the values you give (each value quoted and escaped), without writing SOQL.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Contact` |
| Conditions | `conditions` | Dictionary | No |  | Main | Field to the value it must have; a list means any of them. Every condition must hold. Empty: every record. Example: `{"LastName": "O'Brien", "Account.Industry": ["Retail", "Banking"]}` |
| Fields | `fields` | List | No |  | Main | Fields to read, e.g. ["Id", "Name", "Email"]. Id and Name when empty. |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Order by | `order_by` | Text | No |  | More options | A field to sort by, then ASC or DESC. Example: `CreatedDate DESC` |
| Maximum records | `max_records` | Number | No | `2000` | More options | At most this many records. Range: 1 to  |
| Include deleted | `include_deleted` | Yes/No | No | No | More options | Also return deleted and archived records. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Records, one column per field asked for. |
| `count` | Number | Number of records. |
| `soql` | Text | The SOQL that was run. |

*Time limit 300 s; safe to repeat; needs: network.*

### Get record

`ooa.salesforce/get_record` (action)

Read one record by its Salesforce id, or by the value of an external id field.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Account` |
| ID | `id` | Text | Yes |  | Main | Record id, or the external id value when external_id_field is filled in. |
| Fields | `fields` | List | No |  | Main | Fields to return; all when empty. |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| External ID field | `external_id_field` | Text | No |  | More options | API name of an external id field; when filled in, the record whose field has the value in id is read. Example: `ERP_Number__c` |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `record` | Dictionary | Field values. |

*Time limit 60 s; safe to repeat; needs: network.*

### Query (SOQL)

`ooa.salesforce/query` (action)

Run a SOQL query and return all matching records as a table (all result pages are read).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| SOQL | `soql` | Text | Yes |  | Main | SOQL query. Example: `SELECT Id, Name FROM Account WHERE Industry = 'Retail'` |
| Parameters | `parameters` | Dictionary | No |  | Main | Values for :name in the SOQL, quoted and escaped for you, e.g. WHERE LastName = :name. A list becomes (a, b) for IN. Safer than putting values into the text. Example: `{"name": "O'Brien", "since": "2026-01-01"}` |
| Include deleted | `include_deleted` | Yes/No | No | No | Main | Also return deleted and archived records (queryAll). |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Maximum records | `max_records` | Number | No | `50000` | More options | Safety limit. |
| Batch size | `batch_size` | Number | No | `0` | More options | Records per page (200-2000); Salesforce's default when 0. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Records. |
| `count` | Number | Number of records. |
| `total_size` | Number | Number of matching records Salesforce reports, which can be more than were read. |

*Time limit 300 s; safe to repeat; needs: network.*

### Create, update or upsert many records

`ooa.salesforce/save_records` (action)

Write the rows of a Data Table as records, 200 a call (Salesforce's collections API), and get a result for each row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Contact` |
| Records | `records` | Table | Yes |  | Main | One row per record; the column names are the field API names (an Id column to update). |
| Operation | `operation` | Text | No | `create` | Main | Create: new records. Update: change the records in the Id column. Upsert: create or update by the external id field. One of: `create`, `update`, `upsert` |
| External ID field | `external_id_field` | Text | No |  | Main | For upsert: the external id field that finds an existing record. Example: `ERP_Number__c`; Shown when: `{"operation": ["upsert"]}` |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| All or none | `all_or_none` | Yes/No | No | No | More options | Write a call's records only if all of them can be written (otherwise the good ones are written and the others get an error). |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `results` | Table | One row per record: row (1, 2, ...), id, success, created, error. |
| `succeeded` | Number | How many records were written. |
| `failed` | Number | How many were not. |

*Time limit 3600 s; needs: network.*

### Update record

`ooa.salesforce/update_record` (action)

Change fields of an existing record.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. |
| ID | `id` | Text | Yes |  | Main | Record id. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to new value. |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

*Time limit 60 s; needs: network.*

### Upsert by external id

`ooa.salesforce/upsert_by_external_id` (action)

Update the record whose external id field matches, or create it when none matches.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Object (API name) | `object` | Text | Yes |  | Main | Object API name. Example: `Account` |
| External ID field | `external_id_field` | Text | Yes |  | Main | API name of the external id field. Example: `ERP_Number__c` |
| External ID value | `external_id_value` | Text | Yes |  | Main | External id value to match. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to value (do not repeat the external id field). |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Text | Record id (empty when Salesforce does not return it for an update). |
| `created` | Yes/No | True when a new record was created. |

*Time limit 60 s; safe to repeat; needs: network.*

## Files

### Get attachments

`ooa.salesforce/get_attachments` (action)

List the files and classic attachments on a record, and optionally download them to a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Record ID | `record_id` | Text | Yes |  | Main | Id of the record the files belong to. |
| Download folder | `download_folder` | File path | No |  | Main | Folder to save the files in; list only when empty. |
| Kind | `kind` | Text | No | `all` | Main | Only Salesforce Files (file), only classic attachments (attachment), or both. One of: `all`, `file`, `attachment` |
| Name pattern | `name_pattern` | Text | No |  | Main; tick Only files with matching names | Only files whose name matches this pattern (* and ?, case ignored); all when empty. Example: `*.pdf` |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace files of the same name in the folder; when off, a number is put in front of the new file's name. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, size, content_type, kind (file or attachment), path. |
| `count` | Number | Number of files. |
| `files` | List | Paths of downloaded files (empty when not downloading). |

*Time limit 600 s; safe to repeat; needs: network, filesystem.*

### Upload attachment

`ooa.salesforce/upload_attachment` (action)

Upload a local file and link it to a record as a Salesforce File, or upload it as a new version of an existing file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Record ID | `record_id` | Text | No |  | Main | Id of the record to attach the file to. Not needed when content_document_id is filled in. |
| Path | `path` | File path | Yes |  | Main | File to upload. |
| Title | `title` | Text | No |  | Main | Title shown in Salesforce; the file name when empty. |
| Visibility | `visibility` | Text | No |  | Main | Who can see the file on the record (AllUsers includes community users); Salesforce's default when empty. One of: `AllUsers`, `InternalUsers`, `SharedUsers` |
| Instance address (URL) | `instance_url` | Text | Yes |  | Main | Instance URL, e.g. https://yourcompany.my.salesforce.com. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token. |
| Content document ID | `content_document_id` | Text | No |  | More options | Id of an existing file (starts with 069); the upload becomes its new version and keeps the file's links and sharing. Example: `069000000000001AAA` |
| Description | `description` | Text | No |  | More options | Description stored with the file. |
| API version | `api_version` | Text | No | `v61.0` | More options | REST API version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `content_version_id` | Text | Id of the new file version. |
| `content_document_id` | Text | Id of the file itself (filled when visibility or content_document_id is set). |

*Time limit 600 s; needs: network, filesystem.*
