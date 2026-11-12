# SOAP Web Service

Toolkit `ooa.soap`, version 1.0.0. Read a service's WSDL, call SOAP 1.1/1.2 web services and read the response as a dictionary.

[All toolkits](README.md)

## Contents

- **SOAP**: Read WSDL, Send SOAP request

## SOAP

### Read WSDL

`ooa.soap/read_wsdl` (action)

Read a service's WSDL (address or file) and list its operations, each with its SOAPAction, endpoint, SOAP version and a request body to fill in for Send SOAP request.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Wsdl | `wsdl` | Text | Yes |  | Main | The WSDL address, or a .wsdl file. Example: `https://erp.example.com/OrderService?wsdl` |
| Basic user | `basic_user` | Text | No |  | Main; tick Sign in with a user name | Basic auth user name, when the WSDL needs a sign-in. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; tick Sign in with a user name | Basic auth password from the vault. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Time to wait for each download. Range: 1 to  |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate; for test systems only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `operations` | List | Operations: name, soap_action, endpoint, version (1.1 or 1.2), style, body_template (? where a value goes). |
| `names` | List | The operation names. |

*Time limit 120 s; safe to repeat; needs: network.*

### Send SOAP request

`ooa.soap/send` (action)

Send a SOAP envelope (or just the body, which is wrapped for you) and return the response.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Service endpoint URL, or a path when base_url (or a connection) gives the address. |
| Version | `version` | Text | No | `1.1` | Main | SOAP version. One of: `1.1`, `1.2` |
| Soap action | `soap_action` | Text | No |  | Main | SOAPAction header (from the WSDL). |
| Body | `body` | Text | Yes |  | Main | Full SOAP envelope, or only the body XML (it is wrapped automatically). |
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
| Headers | `headers` | Dictionary | No |  | Main | Extra HTTP headers. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | Response timeout. Range: 1 to  |
| Client certificate | `client_certificate` | File path | No |  | Main; tick Use a client certificate | Client certificate (.p12 or .pfx) for servers that require mutual TLS. |
| Client certificate password | `client_certificate_password` | Credential (vault) | No |  | Main; tick Use a client certificate | Password of the client certificate file. |
| Output file | `output_file` | File path | No |  | Main | Also save the response XML to this file. |
| XPath | `xpath` | Text | No |  | Main | Also return the text of the response nodes this XPath selects, in values. Example: `//*[local-name()='Price']` |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step on a SOAP fault or HTTP error; when false the status and response are returned. |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Proxy | `proxy` | Text | No |  | More options; tick Use a proxy server | Proxy server as host:port; the runner's settings when empty. Example: `proxy.example.com:8080` |
| Proxy user | `proxy_user` | Text | No |  | More options; tick Use a proxy server | Proxy user name, when the proxy needs a sign-in. |
| Proxy password | `proxy_password` | Credential (vault) | No |  | More options; tick Use a proxy server | Proxy password from the vault. |
| Windows sign-in | `windows_auth` | Text | No |  | More options | Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\user) and basic_password. One of: `current_user`, `user_password` |
| Endpoint address | `base_url` | Text | No |  | More options | Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it. Example: `https://api.example.com/v2` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `response` | Text | Response XML. |
| `body` | Any | The first element inside the SOAP Body, as a dictionary. |
| `values` | List | Text of the nodes selected by xpath; empty without xpath. |

*Time limit 120 s; needs: network, filesystem.*
