# MCP tools

Toolkit `ooa.mcp`, version 1.0.0. List and call the tools of an MCP server (Model Context Protocol over HTTP).

[All toolkits](README.md)

## Contents

- **MCP**: Call MCP tool, List MCP tools

## MCP

### Call MCP tool

`ooa.mcp/call_tool` (action)

Call one tool of an MCP server with arguments and return what it answers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Server address (URL) | `server_url` | Text | Yes |  | Main | The MCP server's address (Streamable HTTP). Example: `https://mcp.example.com/mcp` |
| Tool | `tool` | Text | Yes |  | Main | The tool's name (List MCP tools shows them). Example: `lookup_customer` |
| Arguments | `arguments` | Dictionary | No |  | Main | The tool's arguments. |
| Token | `token` | Credential (vault) | No |  | Main | A bearer token, when the server needs one. |
| Fail on tool error | `fail_on_tool_error` | Yes/No | No | Yes | More options | Fail the step when the tool reports an error. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | How long to wait for the answer. Range: 1 to 600 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The text the tool returned. |
| `result` | Any | The tool's structured result, when it gives one. |
| `content` | List | Everything the tool returned (text, images, links). |
| `is_error` | Yes/No | True when the tool reported an error. |

*Time limit 600 s; needs: network.*

### List MCP tools

`ooa.mcp/list_tools` (action)

Connect to an MCP server and list the tools it offers, with what each needs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Server address (URL) | `server_url` | Text | Yes |  | Main | The MCP server's address (Streamable HTTP). Example: `https://mcp.example.com/mcp` |
| Token | `token` | Credential (vault) | No |  | Main | A bearer token, when the server needs one. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait for each answer. Range: 1 to 600 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tools` | List | Each tool: name, description and input_schema. |
| `server` | Dictionary | The server's name and version. |

*Time limit 120 s; safe to repeat; needs: network.*
