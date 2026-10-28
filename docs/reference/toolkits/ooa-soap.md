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
| Address (URL) | `url` | Text | Yes |  | Main | Service endpoint URL. |
| Version | `version` | Text | No | `1.1` | Main | SOAP version. One of: `1.1`, `1.2` |
| Soap action | `soap_action` | Text | No |  | Main | SOAPAction header (from the WSDL). |
| Body | `body` | Text | Yes |  | Main | Full SOAP envelope, or only the body XML (it is wrapped automatically). |
| Basic user | `basic_user` | Text | No |  | Main; tick Sign in with a user name | Basic auth user name. |
| Basic password | `basic_password` | Credential (vault) | No |  | Main; tick Sign in with a user name | Basic auth password from the vault. |
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

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Number | HTTP status code. |
| `response` | Text | Response XML. |
| `body` | Any | The first element inside the SOAP Body, as a dictionary. |
| `values` | List | Text of the nodes selected by xpath; empty without xpath. |

*Time limit 120 s; needs: network.*
