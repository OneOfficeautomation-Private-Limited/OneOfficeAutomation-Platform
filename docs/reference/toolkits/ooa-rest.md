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
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL of the file, or a path when base_url (or a connection) gives the address. |
| Proxy | `proxy` | Text | No |  | Main; tick Use a specific proxy | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | Main; tick Use a specific proxy | Proxy user name, when the proxy needs a sign-in. Shown when: `{"proxy": ["!", ""]}` |
| Proxy password | `proxy_password` | Credential (vault) | No |  | Main; tick Use a specific proxy | Proxy password from the vault. Shown when: `{"proxy": ["!", ""]}` |
| Basic user | `basic_user` | Text | No |  | Main; choice Sign in = User name and password | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; choice Sign in = User name and password | Basic auth password from the vault. |
| Bearer token | `bearer_token` | Credential (vault) | No |  | Main; choice Sign in = Bearer token | Bearer token from the vault. |
| OAuth token address (URL) | `oauth_token_url` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 token endpoint; the request is sent with a token fetched there (kept for the run and renewed before it expires). Example: `https://login.example.com/oauth2/token` |
| OAuth client ID | `oauth_client_id` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client (application) id. |
| OAuth client secret | `oauth_client_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client secret from the vault; needed for client credentials, optional with a refresh token. |
| OAuth refresh token | `oauth_refresh_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | Refresh token from the vault: signs in with the refresh-token grant instead of client credentials. A new refresh token from the server is used for the rest of the run and returned as refresh_token. |
| OAuth scope | `oauth_scope` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Scopes to ask for, separated by spaces. Example: `api://orders/.default` |
| OAuth audience | `oauth_audience` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Audience sent to the token endpoint (some providers, e.g. Auth0, need it). |
| OAuth resource | `oauth_resource` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Resource sent to the token endpoint (e.g. Microsoft Entra v1 and AD FS). |
| OAuth client sign-in | `oauth_client_auth` | Text | No | `basic` | Main; choice Sign in = OAuth 2.0 | How the client id and secret reach the token endpoint: basic (HTTP Basic header, the OAuth standard) or body (form fields client_id and client_secret). One of: `basic`, `body` |
| OAuth 1.0a consumer key | `oauth1_consumer_key` | Text | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer key: the request is signed (RFC 5849). |
| OAuth 1.0a consumer secret | `oauth1_consumer_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer secret from the vault. |
| OAuth 1.0a token | `oauth1_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a access token from the vault; empty for a consumer-only (two-legged) request. |
| OAuth 1.0a token secret | `oauth1_token_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a token secret from the vault. |
| OAuth 1.0a signature method | `oauth1_signature_method` | Text | No | `HMAC-SHA1` | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a signature method. One of: `HMAC-SHA1`, `HMAC-SHA256` |
| Windows user name | `ntlm_user` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows (NTLM) user name for intranet sites, IIS, SharePoint on premises and Exchange. |
| Windows domain | `ntlm_domain` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows domain of the NTLM user; empty when the user name already has it (DOMAIN\user or user@domain). Example: `CONTOSO` |
| Windows password | `ntlm_password` | Credential (vault) | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows password of the NTLM user, from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| Path | `path` | File path | Yes |  | Main | File to save to, or a folder to keep the server's file name. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main | Time allowed for the whole download. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. |
| Windows sign-in | `windows_auth` | Text | No |  | More options | Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\user) and basic_password. One of: `current_user`, `user_password` |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing file. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Endpoint address | `base_url` | Text | No |  | More options | Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it. Example: `https://api.example.com/v2` |

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
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL, or a path such as /invoices/42 when base_url (or a connection) gives the address. |
| Method | `method` | Text | No | `GET` | Main | HTTP method. One of: `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| Proxy | `proxy` | Text | No |  | Main; tick Use a specific proxy | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | Main; tick Use a specific proxy | Proxy user name, when the proxy needs a sign-in. Shown when: `{"proxy": ["!", ""]}` |
| Proxy password | `proxy_password` | Credential (vault) | No |  | Main; tick Use a specific proxy | Proxy password from the vault. Shown when: `{"proxy": ["!", ""]}` |
| Basic user | `basic_user` | Text | No |  | Main; choice Sign in = User name and password | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; choice Sign in = User name and password | Basic auth password from the vault. |
| Bearer token | `bearer_token` | Credential (vault) | No |  | Main; choice Sign in = Bearer token | Bearer token from the vault. |
| OAuth token address (URL) | `oauth_token_url` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 token endpoint; the request is sent with a token fetched there (kept for the run and renewed before it expires). Example: `https://login.example.com/oauth2/token` |
| OAuth client ID | `oauth_client_id` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client (application) id. |
| OAuth client secret | `oauth_client_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client secret from the vault; needed for client credentials, optional with a refresh token. |
| OAuth refresh token | `oauth_refresh_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | Refresh token from the vault: signs in with the refresh-token grant instead of client credentials. A new refresh token from the server is used for the rest of the run and returned as refresh_token. |
| OAuth scope | `oauth_scope` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Scopes to ask for, separated by spaces. Example: `api://orders/.default` |
| OAuth audience | `oauth_audience` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Audience sent to the token endpoint (some providers, e.g. Auth0, need it). |
| OAuth resource | `oauth_resource` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Resource sent to the token endpoint (e.g. Microsoft Entra v1 and AD FS). |
| OAuth client sign-in | `oauth_client_auth` | Text | No | `basic` | Main; choice Sign in = OAuth 2.0 | How the client id and secret reach the token endpoint: basic (HTTP Basic header, the OAuth standard) or body (form fields client_id and client_secret). One of: `basic`, `body` |
| OAuth 1.0a consumer key | `oauth1_consumer_key` | Text | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer key: the request is signed (RFC 5849). |
| OAuth 1.0a consumer secret | `oauth1_consumer_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer secret from the vault. |
| OAuth 1.0a token | `oauth1_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a access token from the vault; empty for a consumer-only (two-legged) request. |
| OAuth 1.0a token secret | `oauth1_token_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a token secret from the vault. |
| OAuth 1.0a signature method | `oauth1_signature_method` | Text | No | `HMAC-SHA1` | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a signature method. One of: `HMAC-SHA1`, `HMAC-SHA256` |
| Windows user name | `ntlm_user` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows (NTLM) user name for intranet sites, IIS, SharePoint on premises and Exchange. |
| Windows domain | `ntlm_domain` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows domain of the NTLM user; empty when the user name already has it (DOMAIN\user or user@domain). Example: `CONTOSO` |
| Windows password | `ntlm_password` | Credential (vault) | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows password of the NTLM user, from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| JSON | `json` | Any | No |  | Main; choice Body = JSON | JSON body (sets Content-Type). Shown when: `{"method": ["!", "GET"], "form": [""], "body": [""]}` |
| Form | `form` | Dictionary | No |  | Main; choice Body = Form fields | Form fields (application/x-www-form-urlencoded). Shown when: `{"method": ["!", "GET"], "json": [""], "body": [""]}` |
| Multipart | `multipart` | Dictionary | No |  | Main; choice Body = Multipart form | Multipart form (multipart/form-data): field name to text, or to {"file": path, "content_type": type} for a file part. Shown when: `{"method": ["!", "GET"]}` |
| Body | `body` | Text | No |  | Main; choice Body = Text | Raw text body. Shown when: `{"method": ["!", "GET"], "json": [""], "form": [""]}` |
| Body file | `body_file` | File path | No |  | Main; choice Body = A file | File sent as the raw body, byte for byte; content_type gives its type. Shown when: `{"method": ["!", "GET"]}` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | Response timeout. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. Shown when: `{"client_certificate": ["!", ""]}` |
| Windows sign-in | `windows_auth` | Text | No |  | More options | Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\user) and basic_password. One of: `current_user`, `user_password` |
| Content type | `content_type` | Text | No |  | More options | Content-Type of a text or file body, e.g. application/xml, text/plain or application/pdf. Example: `application/xml` |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Raise REST.HTTP_ERROR on 4xx/5xx. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Accept cookies | `accept_cookies` | Yes/No | No | No | More options | Keep cookies the server sets and send them back on later requests of this run. |
| Follow redirects | `follow_redirects` | Yes/No | No | Yes | More options | Follow redirects (3xx) automatically. |
| Endpoint address | `base_url` | Text | No |  | More options | Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it. Example: `https://api.example.com/v2` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `body` | Text | Response text. |
| `json` | Any | Parsed JSON body, or empty. |
| `headers` | Dictionary | Response headers. |
| `refresh_token` | Credential (vault) | With an OAuth 2.0 refresh token: the newest refresh token (the server may issue a new one); save it to the vault for the next run. Empty otherwise. |

*Time limit 120 s; needs: network, filesystem.*

### Upload file

`ooa.rest/upload_file` (action)

Send a file to a URL as a multipart form upload, optionally with extra form fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Absolute URL that accepts the upload, or a path when base_url (or a connection) gives the address. |
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
| OAuth token address (URL) | `oauth_token_url` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 token endpoint; the request is sent with a token fetched there (kept for the run and renewed before it expires). Example: `https://login.example.com/oauth2/token` |
| OAuth client ID | `oauth_client_id` | Text | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client (application) id. |
| OAuth client secret | `oauth_client_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | OAuth 2.0 client secret from the vault; needed for client credentials, optional with a refresh token. |
| OAuth refresh token | `oauth_refresh_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 2.0 | Refresh token from the vault: signs in with the refresh-token grant instead of client credentials. A new refresh token from the server is used for the rest of the run and returned as refresh_token. |
| OAuth scope | `oauth_scope` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Scopes to ask for, separated by spaces. Example: `api://orders/.default` |
| OAuth audience | `oauth_audience` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Audience sent to the token endpoint (some providers, e.g. Auth0, need it). |
| OAuth resource | `oauth_resource` | Text | No |  | Main; choice Sign in = OAuth 2.0 | Resource sent to the token endpoint (e.g. Microsoft Entra v1 and AD FS). |
| OAuth client sign-in | `oauth_client_auth` | Text | No | `basic` | Main; choice Sign in = OAuth 2.0 | How the client id and secret reach the token endpoint: basic (HTTP Basic header, the OAuth standard) or body (form fields client_id and client_secret). One of: `basic`, `body` |
| OAuth 1.0a consumer key | `oauth1_consumer_key` | Text | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer key: the request is signed (RFC 5849). |
| OAuth 1.0a consumer secret | `oauth1_consumer_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a consumer secret from the vault. |
| OAuth 1.0a token | `oauth1_token` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a access token from the vault; empty for a consumer-only (two-legged) request. |
| OAuth 1.0a token secret | `oauth1_token_secret` | Credential (vault) | No |  | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a token secret from the vault. |
| OAuth 1.0a signature method | `oauth1_signature_method` | Text | No | `HMAC-SHA1` | Main; choice Sign in = OAuth 1.0a | OAuth 1.0a signature method. One of: `HMAC-SHA1`, `HMAC-SHA256` |
| Windows user name | `ntlm_user` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows (NTLM) user name for intranet sites, IIS, SharePoint on premises and Exchange. |
| Windows domain | `ntlm_domain` | Text | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows domain of the NTLM user; empty when the user name already has it (DOMAIN\user or user@domain). Example: `CONTOSO` |
| Windows password | `ntlm_password` | Credential (vault) | No |  | Main; choice Sign in = Windows user name and password, NTLM | Windows password of the NTLM user, from the vault. |
| Headers | `headers` | Dictionary | No |  | Main | Request headers. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main | Response timeout. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Send a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Send a client certificate | Password of the client certificate file. |
| Query | `query` | Dictionary | No |  | More options | Query parameters, URL-encoded automatically. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Raise REST.HTTP_ERROR on 4xx/5xx. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Endpoint address | `base_url` | Text | No |  | More options | Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it. Example: `https://api.example.com/v2` |
| Windows sign-in | `windows_auth` | Text | No |  | More options | Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\user) and basic_password. One of: `current_user`, `user_password` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `body` | Text | Response text. |
| `json` | Any | Parsed JSON body, or empty. |

*Time limit 3600 s; needs: network, filesystem.*
