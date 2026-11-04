# SAP BAPI

Toolkit `ooa.sap_bapi`, version 1.0.0. Call SAP BAPIs and RFC function modules through SAP JCo: connect, run a function with imports, structures and tables, commit or roll back, read tables and function details.

[All toolkits](README.md)

## Contents

- **Functions**: Run BAPI / function, Commit, Get function details, Roll back
- **Session**: Connect, Disconnect
- **Tables**: Read table

## Functions

### Run BAPI / function

`ooa.sap_bapi/call_function` (action)

Run a BAPI or remote-enabled function module with import values (structures as dictionaries) and table rows; returns exports, tables, changing values and the RETURN messages. Fails on error messages (type E or A) unless told otherwise.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Function | `function` | Text | Yes |  | Main | Function module or BAPI name. Example: `BAPI_MATERIAL_GET_DETAIL` |
| Imports | `imports` | Dictionary | No |  | Main | Import parameters: {"MATERIAL": "100-100", "HEADDATA": {"MATERIAL": "X"}}. |
| Tables | `tables` | Dictionary | No |  | Main | Table parameters: name to a Table or a list of dictionaries (one per row). |
| Changing | `changing` | Dictionary | No |  | Main | Changing parameters. |
| Commit | `commit` | Yes/No | No | No | Main | Run BAPI_TRANSACTION_COMMIT (wait) right after a successful call. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Stop with SAP_BAPI.FAILED when RETURN has an error (type E or A); the call is rolled back. |
| Session | `session` | Text | No | `sap` | More options | Session name; one SAP logon per session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exports` | Dictionary | Export parameters (structures as dictionaries, tables as Tables). |
| `tables` | Dictionary | Every table parameter after the call, as Tables. |
| `changing` | Dictionary | Changing parameters after the call. |
| `messages` | List | BAPI RETURN messages (type, id, number, message). |
| `success` | Yes/No | True when no RETURN message has type E or A. |

*Time limit 600 s; needs: network.*

### Commit

`ooa.sap_bapi/commit` (action)

Commit the work of the BAPIs called in this session (BAPI_TRANSACTION_COMMIT), waiting for the update by default.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Wait | `wait` | Yes/No | No | Yes | Main | Wait until the update is done (WAIT = X). |
| Session | `session` | Text | No | `sap` | Main | Session name; one SAP logon per session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `messages` | List | RETURN messages of the commit. |

*Time limit 300 s; needs: network.*

### Get function details

`ooa.sap_bapi/get_function_info` (action)

List the parameters of a BAPI or function module (direction, type, optional, description and the fields of structures and tables), to see what to fill in.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Function | `function` | Text | Yes |  | Main | Function module or BAPI name. |
| Session | `session` | Text | No | `sap` | Main | Session name; one SAP logon per session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `parameters` | List | One dictionary per parameter: name, direction, type, optional, description, fields. |

*Time limit 120 s; safe to repeat; needs: network.*

### Roll back

`ooa.sap_bapi/rollback` (action)

Undo the work of the BAPIs called in this session since the last commit (BAPI_TRANSACTION_ROLLBACK).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `sap` | Main | Session name; one SAP logon per session. |

*Time limit 120 s; needs: network.*

## Session

### Connect

`ooa.sap_bapi/connect` (action)

Log on to an SAP system through SAP JCo (application server or message server with a logon group, optional SAP router and SNC) and keep the logon for the following steps.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | No |  | Main; choice Server = Application server | Application server host name or IP address. |
| System number | `system_number` | Text | No |  | Main; choice Server = Application server | Two-digit SAP system (instance) number. Example: `00` |
| Message server | `message_server` | Text | No |  | Main; choice Server = Message server and logon group | Message server host, for load balancing instead of one application server. |
| System ID | `system_id` | Text | No |  | Main; choice Server = Message server and logon group | Three-character system ID (with a message server). |
| Logon group | `logon_group` | Text | No | `PUBLIC` | Main; choice Server = Message server and logon group | Logon group (with a message server). |
| Client | `client` | Text | Yes |  | Main | Three-digit SAP client. Example: `100` |
| User | `user` | Text | No |  | Main | SAP user name (empty with SNC single sign-on). |
| Password | `password` | Credential (vault) | No |  | Main | SAP password from the vault (empty with SNC single sign-on). |
| Language | `language` | Text | No | `EN` | Main | Logon language, e.g. EN or DE. |
| Router | `router` | Text | No |  | More options | SAP router string. Example: `/H/saprouter.example.com/S/3299/H/` |
| Snc partner name | `snc_partner_name` | Text | No |  | More options | SNC name of the SAP system; turns on SNC (secure, single sign-on). Example: `p:CN=SAP-ERP, O=Example` |
| Snc library | `snc_library` | File path | No |  | More options | Path of the SNC library (e.g. sapcrypto.dll) when SNC is used. |
| Properties | `properties` | Dictionary | No |  | More options | Other JCo destination properties, e.g. {"jco.destination.pool_capacity": "3"}. |
| Session | `session` | Text | No | `sap` | More options | Session name; one SAP logon per session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `system` | Dictionary | system_id, client, user, host and release of the logon. |

*Time limit 120 s; needs: network, filesystem.*

### Disconnect

`ooa.sap_bapi/disconnect` (action)

End the SAP logon of the session (uncommitted work is not saved).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `sap` | Main | Session name; one SAP logon per session. |

*Time limit 60 s; needs: network.*

## Tables

### Read table

`ooa.sap_bapi/read_table` (action)

Read rows of an SAP table or view with RFC_READ_TABLE: chosen fields, a WHERE condition in ABAP syntax and a row limit; values come back as text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | Yes |  | Main | SAP table or view name. Example: `MARA` |
| Fields | `fields` | List | No |  | Main | Fields to read, e.g. ["MATNR", "MTART"]; all fields when empty (only for narrow tables). |
| Where | `where` | Text | No |  | Main | WHERE condition in ABAP Open SQL syntax. Example: `MTART = 'FERT' AND ERSDA >= '20260101'` |
| Maximum rows | `max_rows` | Number | No | `1000` | Main | Most rows to read (0 = no limit). Range: 0 to  |
| Skip rows | `skip_rows` | Number | No | `0` | More options | Rows to skip first, for reading in pages. Range: 0 to  |
| Session | `session` | Text | No | `sap` | More options | Session name; one SAP logon per session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | The rows read, one column per field. |
| `row_count` | Number | Number of rows read. |

*Time limit 600 s; safe to repeat; needs: network.*
