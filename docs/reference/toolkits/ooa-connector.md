# Custom Connectors

Toolkit `ooa.connector`, version 1.0.0. Call any REST API imported from an OpenAPI, Swagger or Postman file as named operations with typed inputs and authentication.

[All toolkits](README.md)

## Contents

- **Connectors**: Call operation, Describe operation, Import connector, List connectors, List operations

## Connectors

### Call operation

`ooa.connector/call_operation` (action)

Call an operation of a custom connector. Checks required parameters, encodes path and query values, signs the request and returns status, headers and the parsed body, or saves a file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Connector | `connector` | Text | Yes |  | Main | Connector name (the file connectors/<name>.connector.json in the workspace). |
| Operation | `operation` | Text | Yes |  | Main | Operation id, as shown by List operations. |
| Parameters | `parameters` | Dictionary | No |  | Main | Path, query, header and cookie parameters by name. |
| Body | `body` | Any | No |  | Main | Request body: a dictionary or list for JSON, fields for forms (file fields take a file path), or a file path for binary uploads. |
| Credential | `credential` | Credential (vault) | No |  | Main | Secret for the API: API key, password, bearer token, OAuth client secret or AWS secret key. May also be a JSON object with the fields of 'auth'. |
| Sign-in settings | `auth` | Dictionary | No |  | Main | Non-secret auth settings: scheme, username, client_id, scope, access_key_id, session_token, region, service. |
| Output file | `output_file` | Text | No |  | More options | Save the response body to this file (streamed; for downloads and binary responses). |
| Endpoint address | `base_url` | Text | No |  | More options | Server to call instead of the connector's server. |
| Headers | `headers` | Dictionary | No |  | More options | Extra request headers. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Response timeout. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Raise CONNECTOR.HTTP_<status> on 4xx and 5xx responses. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `headers` | Dictionary | Response headers. |
| `body` | Any | Response body: parsed JSON, or text; empty when saved to a file. |
| `file` | Text | Path of the saved response when output_file is set. |

*Time limit 300 s; needs: network.*

### Describe operation

`ooa.connector/describe_operation` (action)

Show one operation of a custom connector: parameters, request body, responses and authentication.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Connector | `connector` | Text | Yes |  | Main | Connector name (the file connectors/<name>.connector.json in the workspace). |
| Operation | `operation` | Text | Yes |  | Main | Operation id, as shown by List operations. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `operation` | Dictionary | The full operation definition. |
| `example_parameters` | Dictionary | Parameter names with their default or example value, ready to edit. |
| `auth` | List | Authentication schemes this operation accepts. |

*Time limit 60 s; safe to repeat.*

### Import connector

`ooa.connector/import_connector` (action)

Create or replace a custom connector from an OpenAPI 3.x or Swagger 2.0 document (JSON, or YAML where a YAML parser is installed) or a Postman v2.1 collection.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Spec file | `spec_file` | Text | No |  | Main; choice API description = A file | Path of the API description file. |
| Spec text | `spec_text` | Text | No |  | Main; choice API description = Pasted text | The API description itself, when there is no file. |
| Name | `name` | Text | No |  | Main | Connector name; derived from the API title when empty. |
| Server | `server` | Text | No |  | More options | Base URL to use instead of the first server in the file. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a connector with the same name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `connector` | Text | Name of the saved connector. |
| `operations` | Number | Number of operations imported. |
| `path` | Text | Workspace path of the connector definition. |

*Time limit 60 s; needs: filesystem.*

### List connectors

`ooa.connector/list_connectors` (action)

List the custom connectors in the workspace with their title, server and number of operations.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `connectors` | List | One dictionary per connector: name, title, server, operations, source. |

*Time limit 60 s; safe to repeat.*

### List operations

`ooa.connector/list_operations` (action)

List the operations of a custom connector: id, name, method, path and description.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Connector | `connector` | Text | Yes |  | Main | Connector name (the file connectors/<name>.connector.json in the workspace). |
| Search | `search` | Text | No |  | Main | Only operations whose id, name, path or tag contains this text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `operations` | List | One dictionary per operation: id, name, method, path, description, tags. |
| `count` | Number | Number of operations listed. |

*Time limit 60 s; safe to repeat.*
