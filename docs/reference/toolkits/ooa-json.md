# JSON

Toolkit `ooa.json`, version 1.0.0. Parse, query and write JSON documents.

[All toolkits](README.md)

## Contents

- **JSON**: Delete node, Get node value, Get node list, Get several node values, Insert node, Parse JSON, Read JSON file, Convert to JSON text, Convert to table, Update node, Validate JSON, Write JSON file

## JSON

### Delete node

`ooa.json/delete_node` (action)

Remove the node at a path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary, List or JSON text. |
| Path | `path` | Text | Yes |  | Main | Path of the node to remove. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |
| `removed` | Any | The value that was removed. |

*Time limit 60 s; safe to repeat.*

### Get node value

`ooa.json/get_node` (action)

Read a value by dotted path, e.g. order.items[0].sku.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary or List to query. |
| Path | `path` | Text | Yes |  | Main | Dotted path with [n] indexes. |
| Default | `default` | Any | No |  | More options | Value returned when the path does not exist. |
| Fail if missing | `fail_if_missing` | Yes/No | No | No | More options | Fail with JSON.PATH_NOT_FOUND when the path does not exist. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value at the path, or the default when the path is missing. |
| `found` | Yes/No | True when the path exists. |

*Time limit 60 s; safe to repeat.*

### Get node list

`ooa.json/get_node_array` (action)

Return the list found at a path, ready to loop over.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary, List or JSON text. |
| Path | `path` | Text | No |  | Main | Path of the list; the whole document when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `items` | List | Items of the list. |
| `count` | Number | Number of items. |

*Time limit 60 s; safe to repeat.*

### Get several node values

`ooa.json/get_nodes` (action)

Read several paths at once into one dictionary, e.g. {"id": "order.id", "email": "customer.email"}, optionally converting each value to a type.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary, List or JSON text. |
| Paths | `paths` | Any | Yes |  | Main | Dictionary of result name to path, or a list of paths (each path is then its own name). A path may instead be {"path": "order.total", "type": "Number"} to convert the value to String, Number, DateTime or Boolean. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | Name to value; missing paths give empty values. |
| `missing` | List | Names whose path does not exist. |

*Time limit 60 s; safe to repeat.*

### Insert node

`ooa.json/insert_node` (action)

Add a new key to a dictionary, or insert an item into a list, at a path such as order.items[1] or /order/items/-.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary, List or JSON text. |
| Path | `path` | Text | Yes |  | Main | Path of the new node; its parent must exist. Use - or the list size to append. Example: `order.items[0].note` |
| Node | `node` | Any | No |  | Main | Value to insert. |
| If exists | `if_exists` | Text | No | `error` | Main | When the key already exists: fail with JSON.NODE_EXISTS, replace its value, or leave it. One of: `error`, `overwrite`, `skip` |
| Create parents | `create_parents` | Yes/No | No | No | More options | Create missing parent dictionaries on the way. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |

*Time limit 60 s; safe to repeat.*

### Parse JSON

`ooa.json/parse` (action)

Parse JSON text into a Dictionary or List.

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

Read and parse a JSON file.

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

Serialise a value to JSON text.

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

Turn a JSON array of objects into a table; each key becomes a column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | List of dictionaries, or JSON text. |
| Path | `path` | Text | No |  | Main | Path of the array inside the document; the whole document when empty. |
| Flatten | `flatten` | Yes/No | No | No | More options | Turn nested objects into dotted columns, e.g. customer.name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table with one row per object. |

*Time limit 60 s; safe to repeat.*

### Update node

`ooa.json/update_node` (action)

Replace the value of an existing node at a path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | Yes |  | Main | Dictionary, List or JSON text. |
| Path | `path` | Text | Yes |  | Main | Path of the node to change. |
| Node | `node` | Any | No |  | Main | New value. |
| Create if missing | `create_if_missing` | Yes/No | No | No | More options | Add the key when it does not exist instead of failing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Updated document. |
| `previous` | Any | The value that was replaced. |

*Time limit 60 s; safe to repeat.*

### Validate JSON

`ooa.json/validate` (action)

Check that JSON is well-formed, that required paths exist and, optionally, that it matches a JSON Schema (common keywords).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| JSON | `json` | Any | Yes |  | Main | JSON text, Dictionary or List to check. |
| Required keys | `required_keys` | List | No |  | Main | Paths that must exist, e.g. ["id", "customer.email"]. |
| Schema | `schema` | Any | No |  | Main; choice Schema = Schema text | JSON Schema as a Dictionary or JSON text: type, properties, required, items, enum, const, minimum/maximum, length and pattern rules, allOf/anyOf/oneOf/not and local $ref. |
| Schema path | `schema_path` | File path | No |  | Main; choice Schema = Schema file | JSON Schema file; used when 'schema' is empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the JSON parses and every required path exists. |
| `errors` | List | Problems found, each with the path where it was found. |
| `level` | Text | What was checked: syntax (the text did not parse), structure or schema. |

*Time limit 60 s; safe to repeat.*

### Write JSON file

`ooa.json/write_file` (action)

Write a value to a JSON file atomically.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Target path. |
| Value | `value` | Any | Yes |  | Main | Value to write. |
| Pretty | `pretty` | Yes/No | No | No | Main | Write indented JSON for reading. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Text encoding, e.g. utf-8, utf-8-bom, utf-16. |

*Time limit 60 s; needs: filesystem.*
