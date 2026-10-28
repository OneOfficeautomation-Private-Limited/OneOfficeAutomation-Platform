# Workday

Toolkit `ooa.workday`, version 1.0.0. Search and read Workday workers and call Workday REST endpoints or RaaS reports.

[All toolkits](README.md)

## Contents

- **Authentication**: Authenticate
- **REST**: Call REST endpoint (GET)
- **Integrations**: Get integration event, Launch integration
- **Reports**: Get report (RaaS)
- **Workers**: Search workers

## Authentication

### Authenticate

`ooa.workday/authenticate` (action)

Get an OAuth access token for a registered API client from its refresh token, for the other Workday actions.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Tenant | `tenant` | Text | Yes |  | Main | Tenant name. |
| Client ID | `client_id` | Text | Yes |  | Main | Client id of the API client registered in Workday. |
| Client secret | `client_secret` | Credential (vault) | Yes |  | Main | Client secret from the vault. |
| Refresh token | `refresh_token` | Credential (vault) | Yes |  | Main | Refresh token issued to the API client, from the vault. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `access_token` | Credential (vault) | Access token; map it to access_token of later actions. |
| `expires_in` | Number | Seconds until the access token expires, when Workday says. |

*Time limit 60 s; needs: network.*

## REST

### Call REST endpoint (GET)

`ooa.workday/get` (action)

GET any Workday REST resource or RaaS report URL path and return the JSON.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Path | `path` | Text | Yes |  | Main | Path starting with /ccx/. Example: `/ccx/api/v1/acme/workers/3aa5550b7fe348b98d7b5741afc65534` |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `json` | Any | Response body. |

*Time limit 300 s; safe to repeat; needs: network.*

## Integrations

### Get integration event

`ooa.workday/get_integration_event` (action)

Read the status and timing of an integration run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Tenant | `tenant` | Text | Yes |  | Main | Tenant name. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Integration event WID from Launch integration. |
| User | `user` | Text | No |  | More options; choice Sign in with = User name and password | Integration system user with tenant (basic sign-in). |
| Password | `password` | Credential (vault) | No |  | More options; choice Sign in with = User name and password | Password from the vault (basic sign-in). |
| Access token | `access_token` | Credential (vault) | No |  | More options; choice Sign in with = An access token | OAuth access token from the vault (instead of user and password). |
| Version | `version` | Text | No | `v42.0` | More options | Web service version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Run status, e.g. Processing, Completed, Completed with Warnings or Failed. |
| `percent_complete` | Number | Progress percentage when Workday reports it. |
| `started` | Text | Start time. |
| `completed` | Text | Completion time; empty while running. |
| `done` | Yes/No | True once the run has finished, whether it succeeded or not. |

*Time limit 120 s; safe to repeat; needs: network.*

### Launch integration

`ooa.workday/launch_integration` (action)

Start an integration system (such as an EIB) through the Integrations web service, optionally with launch parameter values, and return the event id to track it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Tenant | `tenant` | Text | Yes |  | Main | Tenant name. |
| Kind of ID | `id_type` | Text | No | `integration_system_id` | Main | Whether integration_system_id holds the Integration System ID or the integration's Workday ID (WID). One of: `integration_system_id`, `wid` |
| Integration system ID | `integration_system_id` | Text | Yes |  | Main | Integration System ID of the integration to start. Example: `INT_Worker_Load` |
| Parameters | `parameters` | Dictionary | No |  | Main | Launch parameter name to value; text, numbers, true/false and dates are sent with their Workday type. Example: `{"Effective_Date": "2026-11-01", "Include_Terminated": false}` |
| User | `user` | Text | No |  | More options; choice Sign in with = User name and password | Integration system user with tenant, e.g. isu_int@acme (basic sign-in). |
| Password | `password` | Credential (vault) | No |  | More options; choice Sign in with = User name and password | Password from the vault (basic sign-in). |
| Access token | `access_token` | Credential (vault) | No |  | More options; choice Sign in with = An access token | OAuth access token from the vault (instead of user and password). |
| Version | `version` | Text | No | `v42.0` | More options | Web service version. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `event_id` | Text | Integration event WID; pass it to Get integration event. |

*Time limit 180 s; needs: network.*

## Reports

### Get report (RaaS)

`ooa.workday/get_report` (action)

Run a custom report exposed as a web service and return its rows as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Report path | `report_path` | Text | Yes |  | Main | Report path starting with /ccx/service/customreport2/. Example: `/ccx/service/customreport2/acme/isu_reports/Active_Workers` |
| Format | `format` | Text | No | `json` | Main | Format to request from Workday: json, csv, simple_xml, or workday_xml (which adds reference ids such as WIDs as extra columns). One of: `json`, `csv`, `simple_xml`, `workday_xml` |
| Parameters | `parameters` | Dictionary | No |  | Main | Report prompts, e.g. {"Effective_as_of_Date": "2026-10-01"}. |
| User | `user` | Text | No |  | More options; choice Sign in with = User name and password | Integration system user (basic sign-in), e.g. isu_reports@acme. |
| Password | `password` | Credential (vault) | No |  | More options; choice Sign in with = User name and password | Password from the vault (basic sign-in). |
| Access token | `access_token` | Credential (vault) | No |  | More options; choice Sign in with = An access token | OAuth access token from the vault (instead of user and password). |
| Save to file | `save_to_file` | File path | No |  | More options; tick Also save the answer to a file | Also save Workday's answer, unchanged, to this file; nothing is saved when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Report rows; columns are the report's field aliases. |
| `count` | Number | Number of rows. |
| `path` | File path | The saved file, when save_to_file was given. |

*Time limit 600 s; safe to repeat; needs: network.*

## Workers

### Search workers

`ooa.workday/search_workers` (action)

Find workers by name or id text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Workday REST host, e.g. https://wd2-impl-services1.workday.com. |
| Tenant | `tenant` | Text | Yes |  | Main | Tenant name. |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | OAuth access token from the vault. |
| Search | `search` | Text | No |  | Main; tick Only matching workers | Name or id text; all workers when empty. |
| Maximum results | `max_results` | Number | No | `100` | More options | Maximum workers. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, descriptor, primary_work_email, business_title. |
| `count` | Number | Number of workers. |

*Time limit 300 s; safe to repeat; needs: network.*
