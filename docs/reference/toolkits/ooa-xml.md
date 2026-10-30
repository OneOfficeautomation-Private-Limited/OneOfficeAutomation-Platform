# XML

Toolkit `ooa.xml`, version 1.0.0. Query and change XML with XPath, convert to dictionaries and validate against XSD.

[All toolkits](README.md)

## Contents

- **XML**: Delete nodes, Get multiple nodes, Get single node, Insert node, Save XML file, Update node, Convert to dictionary, Validate XML, Evaluate XPath
- **Loop types**: Each node in XML

## XML

### Delete nodes

`ooa.xml/delete_nodes` (action)

Remove every node (or one attribute of every node) matching an XPath, in a file or in XML text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| XPath | `xpath` | Text | Yes |  | Main | XPath of the nodes to remove. |
| Attribute | `attribute` | Text | No |  | Main | Remove only this attribute from the matched elements, keeping the elements. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Nodes (or attributes) removed. |
| `xml` | Text | The XML after the change. |

*Time limit 60 s; needs: filesystem.*

### Get multiple nodes

`ooa.xml/get_nodes` (action)

Return the text of every node matching an XPath.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| XPath | `xpath` | Text | Yes |  | Main | XPath expression. Example: `//line/sku` |
| Return value | `return_value` | Text | No | `text` | Main | What to return for each node: its text, its XML, or its exact XPath. One of: `text`, `xml`, `xpath` |
| Attribute | `attribute` | Text | No |  | Main | Return this attribute of each node instead of its text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Texts of matching nodes. |
| `count` | Number | Number of matches. |

*Time limit 60 s; safe to repeat.*

### Get single node

`ooa.xml/get_value` (action)

Return the text of the first node matching an XPath.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| XPath | `xpath` | Text | Yes |  | Main | XPath expression. Example: `/invoice/total` |
| Attribute | `attribute` | Text | No |  | Main | Return this attribute of the node instead of its text. |
| Return value | `return_value` | Text | No | `text` | More options | What to return: the node's text, its XML, or its exact XPath. One of: `text`, `xml`, `xpath` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | Node text, empty when nothing matches. |
| `found` | Yes/No | True when a node matched. |

*Time limit 60 s; safe to repeat.*

### Insert node

`ooa.xml/insert_node` (action)

Add an element (with text and attributes) under, before or after every node matching an XPath.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| Parent XPath | `parent_xpath` | Text | Yes |  | Main | XPath of the parent node(s). |
| Name | `name` | Text | Yes |  | Main | Element name. |
| Text | `text` | Text | No |  | Main | Element text. |
| If exists | `if_exists` | Text | No | `insert` | Main | When the parent already has a child with this name (end/start only): add another, skip, or replace the first one. One of: `insert`, `skip`, `overwrite` |
| Position | `position` | Text | No | `end` | Main | end or start: last or first child of the matched node; before or after: next to the matched node, as its sibling. One of: `end`, `start`, `before`, `after` |
| Attributes | `attributes` | Dictionary | No |  | Main | Attributes to set. |
| Reference child | `reference_child` | Text | No |  | More options | With position before or after: insert next to the first child of the matched node with this element name, instead of next to the matched node itself. Example: `total`; Shown when: `{"position": ["before", "after"]}` |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Namespace declarations put on the new element, as prefix to URI (an empty prefix is the default namespace); name may then use the prefix, e.g. inv:note. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Elements inserted. |
| `xml` | Text | The XML after the change. |

*Time limit 60 s; needs: filesystem.*

### Save XML file

`ooa.xml/save_file` (action)

Check that XML text is well-formed and write it to a file, optionally indented.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| XML | `xml` | Text | Yes |  | Main | XML text to save. |
| Path | `path` | File path | Yes |  | Main | Target file. |
| Indent | `indent` | Yes/No | No | No | Main | Re-format with indentation instead of keeping the text as given. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file that is already there; when off, the step fails instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the written file. |

*Time limit 60 s; needs: filesystem.*

### Update node

`ooa.xml/set_value` (action)

Set the text (or an attribute) of every node matching an XPath, in a file or in XML text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| XPath | `xpath` | Text | Yes |  | Main | XPath of the nodes to change. |
| Value | `value` | Text | No |  | Main | New text (or the attribute's new value). |
| Attribute | `attribute` | Text | No |  | Main | Set this attribute (created when missing) instead of the node text. |
| Attributes | `attributes` | Dictionary | No |  | More options | Several attributes to set at once, as name to value (created when missing). With these, the node text changes only when value is filled in. Example: `{"status": "paid", "currency": "EUR"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Nodes changed. |
| `xml` | Text | The XML after the change. |

*Time limit 60 s; needs: filesystem.*

### Convert to dictionary

`ooa.xml/to_dictionary` (action)

Convert XML (or the part matched by an XPath) to a Dictionary; attributes become @name keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| XPath | `xpath` | Text | No |  | Main | Convert only the first node matching this XPath. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Dictionary or text. |

*Time limit 60 s; safe to repeat.*

### Validate XML

`ooa.xml/validate` (action)

Check that an XML document is well-formed and, when an XSD schema is given, valid against it; returns the result instead of failing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Xsd path | `xsd_path` | File path | No |  | Main; tick Check against a schema | XSD schema file; without it only well-formedness is checked. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the document is valid. |
| `errors` | List | Validation errors with line numbers. |

*Time limit 60 s; safe to repeat.*

### Evaluate XPath

`ooa.xml/xpath_function` (action)

Evaluate an XPath expression such as count(//line) or sum(//amount) and return text, a number or true/false.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Expression | `expression` | Text | Yes |  | Main | XPath expression. Example: `sum(//line/amount)` |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Result type | `result_type` | Text | No | `string` | Main | Type of the result. One of: `string`, `number`, `boolean` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Result of the expression as String, Number or Boolean. |

*Time limit 60 s; safe to repeat.*

## Loop types

Used as the kind of loop in a Loop step.

### Each node in XML

`ooa.xml/nodes` (loop type)

Yield each node matching an XPath: its text, XML, XPath or an attribute.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| XPath | `xpath` | Text | Yes |  | Main | XPath expression. Example: `//line` |
| XML | `xml` | Text | No |  | Main | XML text (or use path). |
| Path | `path` | File path | No |  | Main | XML file (or use xml). |
| Attribute | `attribute` | Text | No |  | Main | Yield this attribute of each node instead of its text. |
| Return value | `return_value` | Text | No | `text` | Main | What to yield for each node: its text, its XML, or its exact XPath. One of: `text`, `xml`, `xpath` |
