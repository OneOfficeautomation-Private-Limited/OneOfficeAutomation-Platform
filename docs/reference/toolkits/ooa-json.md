# JSON

Toolkit `ooa.json`, version 1.0.0. Parse, query and write JSON documents.

[All toolkits](README.md)

## Contents

- **JSON**: Delete node, Get node value, Get node list, Get several node values, Insert node, Parse JSON, Read JSON file, Convert to JSON text, Convert to table, Update node, Validate JSON, Write JSON file
- **JSON sessions**: End session, Start session

## JSON

### Delete node

`ooa.json/delete_node` (action)

Remove one value from JSON, found by its path (order.items[0]).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | Yes |  | Main | Path of the node to remove. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |
| `removed` | Any | The value that was removed. |

*Time limit 60 s; safe to repeat.*

### Get node value

`ooa.json/get_node` (action)

Read one value from JSON by its path, such as order.items[0].sku; $..sku finds every sku, $.items[?(@.qty > 1)] the items with qty over 1.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | Yes |  | Main | Dotted path with [n] indexes, or JSONPath starting with $. Example: `$.order.items[0].sku` |
| Default | `default` | Any | No |  | More options | Value returned when the path does not exist. |
| Fail if missing | `fail_if_missing` | Yes/No | No | No | More options | Fail with JSON.PATH_NOT_FOUND when the path does not exist. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value at the path (a List when the path can match several values: *, .., filters, slices), or the default when nothing matches. |
| `found` | Yes/No | True when the path matched. |

*Time limit 60 s; safe to repeat.*

### Get node list

`ooa.json/get_node_array` (action)

Read the list found at a path, ready to go through with a Loop.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | No |  | Main | Path of the list; the whole document when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `items` | List | Items of the list. |
| `count` | Number | Number of items. |

*Time limit 60 s; safe to repeat.*

### Get several node values

`ooa.json/get_nodes` (action)

Read several values at once into a Dictionary, e.g. {"id": "order.id", "email": "customer.email"}, each turned into a type if you want.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Paths | `paths` | Any | Yes |  | Main | Dictionary of result name to path, or a list of paths (each path is then its own name). A path may instead be {"path": "order.total", "type": "Number"} to convert the value to String, Number, DateTime or Boolean. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | Name to value; missing paths give empty values. |
| `missing` | List | Names whose path does not exist. |

*Time limit 60 s; safe to repeat.*

### Insert node

`ooa.json/insert_node` (action)

Add a value to JSON: a new key, or an item in a list at a position such as order.items[1].

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | Yes |  | Main | Path of the new node; its parent must exist. Use - or the list size to append. Example: `order.items[0].note` |
| Node | `node` | Any | No |  | Main | Value to insert. |
| Value type | `value_type` | Text | No | `as_given` | Main | Type the new value gets in the JSON: as given, text, a number, true/false, JSON (text such as {"a": 1} becomes an object or list) or null. One of: `as_given`, `text`, `number`, `boolean`, `json`, `null` |
| If exists | `if_exists` | Text | No | `error` | Main | When the key already exists: fail with JSON.NODE_EXISTS, replace its value, or leave it. One of: `error`, `overwrite`, `skip` |
| Create parents | `create_parents` | Yes/No | No | No | More options | Create missing parent dictionaries on the way. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |

*Time limit 60 s; safe to repeat.*

### Parse JSON

`ooa.json/parse` (action)

Read JSON text into a Dictionary or a List.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | JSON text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Parsed value. |

*Time limit 60 s; safe to repeat.*

### Read JSON file

`ooa.json/read_file` (action)

Read a JSON file into a Dictionary or a List.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Path to a .json file. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Text encoding of the file, e.g. utf-8, utf-16, ansi. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Parsed content. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Convert to JSON text

`ooa.json/stringify` (action)

Write a value (Dictionary, List, table...) as JSON text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Value to serialise. |
| Pretty | `pretty` | Yes/No | No | No | Main | Spread the JSON over several indented lines for reading. |
| Indent | `indent` | Number | No | `2` | More options | Spaces per level when pretty is on. Range: 0 to 10; Shown when: `{"pretty": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | JSON text. |

*Time limit 60 s; safe to repeat.*

### Convert to table

`ooa.json/to_table` (action)

Turn a JSON list of records into a table: each key becomes a column, each record a row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | List of dictionaries, or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | No |  | Main | Path of the array inside the document; the whole document when empty. |
| Flatten | `flatten` | Yes/No | No | No | More options | Turn nested objects into dotted columns, e.g. customer.name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table with one row per object. |

*Time limit 60 s; safe to repeat.*

### Update node

`ooa.json/update_node` (action)

Change one value in JSON, found by its path; a list there can be replaced or added to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice JSON = A value | Dictionary, List or JSON text (or use an open JSON session). |
| Session | `session` | Text | No |  | Main; choice JSON = A session | Work on the document of this JSON session (Start session) instead of a value; changes stay in the session until End session saves them. |
| Path | `path` | Text | Yes |  | Main | Path of the node to change. |
| Node | `node` | Any | No |  | Main | New value. |
| Value type | `value_type` | Text | No | `as_given` | Main | Type the new value gets in the JSON: as given, text, a number, true/false, JSON (text such as {"a": 1} becomes an object or list) or null. One of: `as_given`, `text`, `number`, `boolean`, `json`, `null` |
| If list | `if_list` | Text | No | `replace` | More options | When the node at the path is a list: replace the whole list with the new value, or add the new value (each item of a new list) at its end. One of: `replace`, `add` |
| Create if missing | `create_if_missing` | Yes/No | No | No | More options | Add the key when it does not exist instead of failing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |
| `previous` | Any | The value that was replaced. |

*Time limit 60 s; safe to repeat.*

### Validate JSON

`ooa.json/validate` (action)

Check JSON: that it is well written, that the paths you need are there and, if you give one, that it follows a JSON Schema.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| JSON | `json` | Any | No |  | Main; choice Check = JSON text or a value | JSON text, Dictionary or List to check (or use a file or a session). |
| Path | `path` | File path | No |  | Main; choice Check = A file | JSON file to check (instead of json). |
| Session | `session` | Text | No |  | Main; choice Check = A session | Check the document of this JSON session (Start session). |
| Required keys | `required_keys` | List | No |  | Main | Paths that must exist, e.g. ["id", "customer.email"]. |
| Required types | `required_types` | Dictionary | No |  | Main | Paths that must exist with a type: String, Number, Boolean, List, Dictionary or Null. Example: `{"id": "Number", "customer.email": "String"}` |
| Schema | `schema` | Any | No |  | Main; choice Schema = Schema text | JSON Schema (draft-07 to 2020-12) as a Dictionary or JSON text; $ref may point inside it, to another file (with schema_path or an $id) or to a URL. |
| Schema path | `schema_path` | File path | No |  | Main; choice Schema = Schema file | JSON Schema file; used when 'schema' is empty. References such as address.json#/$defs/x are read from its folder. |
| Check formats | `check_formats` | Yes/No | No | Yes | More options | Check the format keyword: date-time, date, time, duration, email, hostname, ipv4, ipv6, uri, uuid, regex and json-pointer. |
| Load remote refs | `load_remote_refs` | Yes/No | No | Yes | More options | Follow $ref to other schema files and http(s) URLs (30 s timeout, 5 MB limit); false allows references inside the schema only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the JSON parses and every required path exists. |
| `errors` | List | Problems found, each with the path where it was found. |
| `level` | Text | What was checked: syntax (the text did not parse), structure or schema. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Write JSON file

`ooa.json/write_file` (action)

Save a value to a JSON file (written in one go, so a reader never sees half a file).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Target path. |
| Value | `value` | Any | Yes |  | Main | Value to write. |
| Pretty | `pretty` | Yes/No | No | No | Main | Write indented JSON for reading. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Text encoding, e.g. utf-8, utf-8-bom, utf-16. |

*Time limit 60 s; needs: filesystem.*

## JSON sessions

### End session

`ooa.json/end_session` (action)

Close a JSON session, optionally saving its document to a file first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `json` | Main | Session name. |
| Save to | `save_to` | File path | No |  | Main; tick Save the document | Save the document to this file before closing; not saved when empty. |
| Pretty | `pretty` | Yes/No | No | No | Main; tick Save the document | Write indented JSON for reading. |
| Encoding | `encoding` | Text | No | `utf-8` | Main; tick Save the document | Text encoding of the saved file, e.g. utf-8, utf-8-bom, utf-16. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | The document as the session held it. |
| `path` | File path | The file written; empty when nothing was saved. |

*Time limit 60 s; needs: filesystem.*

### Start session

`ooa.json/start_session` (action)

Load JSON (text, a value or a file) once as a named session, so later steps read and change it without passing it along.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main; choice Load from = Text or a value | JSON text, a Dictionary or a List. |
| Path | `path` | File path | No |  | Main; choice Load from = A file | JSON file to load (or use value). |
| Encoding | `encoding` | Text | No | `utf-8` | Main; choice Load from = A file | Text encoding of the file, e.g. utf-8, utf-16, ansi. Shown when: `{"path": ["!", ""]}` |
| Session | `session` | Text | No | `json` | Main | Session name for later steps. |
| Agents this one runs may use it | `share` | Yes/No | No | No | More options | Agents started from this one with Run agent may use this session by its name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | The loaded document. |

*Time limit 60 s; safe to repeat; needs: filesystem.*
