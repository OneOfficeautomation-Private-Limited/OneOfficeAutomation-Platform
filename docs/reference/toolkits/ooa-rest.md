# REST Web Service

Toolkit `ooa.rest`, version 1.0.0. Call HTTP/REST APIs with JSON, headers and authentication.

[All toolkits](README.md)

## Contents

- **HTTP**: Download file, Send request, Upload file

## HTTP

### Download file

`ooa.rest/download_file` (action)

Download a URL straight to a file (any file type, including large binaries), with the same headers and authentication as Send request.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL of the file. |
| Proxy | `proxy` | Text | No |  | Main; tick Use a specific proxy | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | Main; tick Use a specific proxy | Proxy user name, when the proxy needs a sign-in. Shown when: `{"proxy": ["!", ""]}` |
| Proxy password | `proxy_password` | Credential (vault) | No |  | Main; tick Use a specific proxy | Proxy password from the vault. Shown when: `{"proxy": ["!", ""]}` |
| Basic user | `basic_user` | Text | No |  | Main; choice Sign in = User name and password | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; choice Sign in = User name and password | Basic auth password from the vault. |
| Bearer token | `bearer_token` | Credential (vault) | No |  | Main; choice Sign in = Bearer token | Bearer token from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| Path | `path` | File path | Yes |  | Main | File to save to, or a folder to keep the server's file name. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main | Time allowed for the whole download. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing file. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |
| `status` | Number | HTTP status code. |
| `size_bytes` | Number | File size in bytes. |
| `content_type` | Text | Content-Type reported by the server, or empty. |

*Time limit 3600 s; needs: network, filesystem.*

### Send request

`ooa.rest/request` (action)

Send an HTTP request and return status, headers and body (parsed as JSON when possible).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL. |
| Method | `method` | Text | No | `GET` | Main | HTTP method. One of: `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| Proxy | `proxy` | Text | No |  | Main; tick Use a specific proxy | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | Main; tick Use a specific proxy | Proxy user name, when the proxy needs a sign-in. Shown when: `{"proxy": ["!", ""]}` |
| Proxy password | `proxy_password` | Credential (vault) | No |  | Main; tick Use a specific proxy | Proxy password from the vault. Shown when: `{"proxy": ["!", ""]}` |
| Basic user | `basic_user` | Text | No |  | Main; choice Sign in = User name and password | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; choice Sign in = User name and password | Basic auth password from the vault. |
| Bearer token | `bearer_token` | Credential (vault) | No |  | Main; choice Sign in = Bearer token | Bearer token from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| JSON | `json` | Any | No |  | Main; choice Body = JSON | JSON body (sets Content-Type). Shown when: `{"method": ["!", "GET"], "form": [""], "body": [""]}` |
| Form | `form` | Dictionary | No |  | Main; choice Body = Form fields | Form fields (application/x-www-form-urlencoded). Shown when: `{"method": ["!", "GET"], "json": [""], "body": [""]}` |
| Multipart | `multipart` | Dictionary | No |  | Main; choice Body = Multipart form | Multipart form (multipart/form-data): field name to text, or to {"file": path, "content_type": type} for a file part. Shown when: `{"method": ["!", "GET"]}` |
| Body | `body` | Text | No |  | Main; choice Body = Text | Raw text body. Shown when: `{"method": ["!", "GET"], "json": [""], "form": [""]}` |
| Body file | `body_file` | File path | No |  | Main; choice Body = A file | File sent as the raw body, byte for byte; content_type gives its type. Shown when: `{"method": ["!", "GET"]}` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | Response timeout. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. Shown when: `{"client_certificate": ["!", ""]}` |
| Content type | `content_type` | Text | No |  | More options | Content-Type of a text or file body, e.g. application/xml, text/plain or application/pdf. Example: `application/xml` |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Raise REST.HTTP_ERROR on 4xx/5xx. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Accept cookies | `accept_cookies` | Yes/No | No | No | More options | Keep cookies the server sets and send them back on later requests of this run. |
| Follow redirects | `follow_redirects` | Yes/No | No | Yes | More options | Follow redirects (3xx) automatically. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `body` | Text | Response text. |
| `json` | Any | Parsed JSON body, or empty. |
| `headers` | Dictionary | Response headers. |

*Time limit 120 s; needs: network.*

### Upload file

`ooa.rest/upload_file` (action)

Send a file to a URL as a multipart form upload, optionally with extra form fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL that accepts the upload. |
| Path | `path` | File path | Yes |  | Main | File to upload. |
| Field name | `field_name` | Text | No | `file` | Main | Form field name the server expects for the file. |
| Form | `form` | Dictionary | No |  | Main | Other form fields sent with the file. |
| Method | `method` | Text | No | `POST` | Main | HTTP method. One of: `POST`, `PUT` |
| Proxy | `proxy` | Text | No |  | Main; tick Use a specific proxy | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | Main; tick Use a specific proxy | Proxy user name, when the proxy needs a sign-in. Shown when: `{"proxy": ["!", ""]}` |
| Proxy password | `proxy_password` | Credential (vault) | No |  | Main; tick Use a specific proxy | Proxy password from the vault. Shown when: `{"proxy": ["!", ""]}` |
| Basic user | `basic_user` | Text | No |  | Main; choice Sign in = User name and password | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; choice Sign in = User name and password | Basic auth password from the vault. |
| Bearer token | `bearer_token` | Credential (vault) | No |  | Main; choice Sign in = Bearer token | Bearer token from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main | Response timeout. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Raise REST.HTTP_ERROR on 4xx/5xx. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `body` | Text | Response text. |
| `json` | Any | Parsed JSON body, or empty. |

*Time limit 3600 s; needs: network, filesystem.*
