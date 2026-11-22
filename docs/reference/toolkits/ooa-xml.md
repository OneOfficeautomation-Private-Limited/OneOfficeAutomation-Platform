# XML

Toolkit `ooa.xml`, version 1.0.0. Query and change XML with XPath, convert to dictionaries and validate against XSD.

[All toolkits](README.md)

## Contents

- **XML**: Delete nodes, Get multiple nodes, Get single node, Insert node, Save XML file, Update node, Convert to dictionary, Validate XML, Evaluate XPath
- **XML sessions**: End session, Start session
- **Loop types**: Each node in XML

## XML

### Delete nodes

`ooa.xml/delete_nodes` (action)

Remove every element (or one attribute of each) that a path such as //order/note finds, in a file, in XML text or in a session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| XPath | `xpath` | Text | Yes |  | Main | XPath of the nodes to remove. |
| Attribute | `attribute` | Text | No |  | Main | Remove only this attribute from the matched elements, keeping the elements. |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Nodes (or attributes) removed. |
| `xml` | Text | The XML after the change. |

*Time limit 60 s; needs: filesystem.*

### Get multiple nodes

`ooa.xml/get_nodes` (action)

Read the text of every element a path such as //order/id finds, as a list and as a table (one row per node, a column per part you name).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| XPath | `xpath` | Text | Yes |  | Main | XPath expression. Example: `//line/sku` |
| Return value | `return_value` | Text | No | `text` | Main | What to return for each node: its text, its XML, or its exact XPath. One of: `text`, `xml`, `xpath` |
| Attribute | `attribute` | Text | No |  | Main | Return this attribute of each node instead of its text. |
| Columns | `columns` | Dictionary | No |  | Main | Columns of the table output: column name to an XPath read from each matching node (a child, an @attribute, or . for the node's own text). Example: `{"sku": "sku", "qty": "@qty", "price": "price/amount"}` |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Texts of matching nodes. |
| `count` | Number | Number of matches. |
| `table` | Table | One row per matching node: the columns you named, or value and xpath when none are named. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Get single node

`ooa.xml/get_value` (action)

Read the text of the first element a path such as //order/id finds.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| XPath | `xpath` | Text | Yes |  | Main | XPath expression. Example: `/invoice/total` |
| Attribute | `attribute` | Text | No |  | Main | Return this attribute of the node instead of its text. |
| Return value | `return_value` | Text | No | `text` | More options | What to return: the node's text, its XML, or its exact XPath. One of: `text`, `xml`, `xpath` |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | Node text, empty when nothing matches. |
| `found` | Yes/No | True when a node matched. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Insert node

`ooa.xml/insert_node` (action)

Add an element, with its text and attributes, under, before or after every element a path finds.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
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

Check that XML text is well written and save it to a file, indented if you want.

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

Change the text (or an attribute) of every element a path finds, in a file, in XML text or in a session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| Save to | `save_to` | File path | No |  | Main; choice Source = An XML file | Save to another file instead of overwriting. |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text to change instead of a file; the result is in the xml output. |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| XPath | `xpath` | Text | Yes |  | Main | XPath of the nodes to change. |
| Value | `value` | Text | No |  | Main | New text (or the attribute's new value). |
| Attribute | `attribute` | Text | No |  | Main | Set this attribute (created when missing) instead of the node text. |
| Attributes | `attributes` | Dictionary | No |  | More options | Several attributes to set at once, as name to value (created when missing). With these, the node text changes only when value is filled in. Example: `{"status": "paid", "currency": "EUR"}` |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Nodes changed. |
| `xml` | Text | The XML after the change. |

*Time limit 60 s; needs: filesystem.*

### Convert to dictionary

`ooa.xml/to_dictionary` (action)

Turn XML (or the part a path finds) into a Dictionary; attributes become keys starting with @.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| XPath | `xpath` | Text | No |  | Main | Convert only the first node matching this XPath. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Dictionary or text. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Validate XML

`ooa.xml/validate` (action)

Check that XML is well written and, if you give them, that it follows one or more XSD schemas or its DTD; gives the result instead of failing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Session | `session` | Text | No |  | Main; choice Source = A session | Check the XML of this session (Start session). |
| Xsd path | `xsd_path` | File path | No |  | Main; tick Check against XSD schemas | XSD schema file; without it only well-formedness is checked. |
| More schemas | `more_schemas` | List | No |  | Main; tick Check against XSD schemas | Further XSD files the document must follow together with xsd_path, e.g. one per namespace. |
| Check against the DTD | `check_dtd` | Yes/No | No | No | Main; tick Check against a DTD | Check the document against its DTD: the one inside its DOCTYPE, or the file in dtd_path (a DTD the DOCTYPE points to elsewhere is never downloaded). |
| Dtd path | `dtd_path` | File path | No |  | Main; tick Check against a DTD | DTD file to check against; used for the DOCTYPE's outside DTD, or added when the document has no DOCTYPE. Shown when: `{"check_dtd": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the document is valid. |
| `errors` | List | Validation errors with line numbers. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Evaluate XPath

`ooa.xml/xpath_function` (action)

Work something out from XML with an XPath function, such as count(//line) or sum(//amount): text, a number or true/false.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Expression | `expression` | Text | Yes |  | Main | XPath expression. Example: `sum(//line/amount)` |
| Path | `path` | File path | No |  | Main; choice Source = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Source = XML text | XML text (or use path). |
| Session | `session` | Text | No |  | Main; choice Source = A session | Work on the XML of this session (Start session) instead of a file or text; changes stay in the session until End session saves them. |
| Result type | `result_type` | Text | No | `string` | Main | Type of the result. One of: `string`, `number`, `boolean` |
| Namespaces | `namespaces` | Dictionary | No |  | More options | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Result of the expression as String, Number or Boolean. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

## XML sessions

### End session

`ooa.xml/end_session` (action)

Close an XML session, optionally saving its XML first: over the file it was loaded from, or to another file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `xml` | Main | Session name. |
| Save over the loaded file | `save_in_place` | Yes/No | No | No | Main; tick Save the XML | Write the XML back to the file Start session loaded. |
| Save to | `save_to` | File path | No |  | Main; tick Save the XML | Write the XML to this file (used when not saving over the loaded file). |
| Indent | `indent` | Yes/No | No | No | Main; tick Save the XML | Re-format the saved XML with indentation. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `xml` | Text | The XML as the session held it. |
| `path` | File path | The file written; empty when nothing was saved. |

*Time limit 60 s; needs: filesystem.*

### Start session

`ooa.xml/start_session` (action)

Load XML (a file or text) once as a named session, so later steps read and change it and End session saves it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | No |  | Main; choice Load from = An XML file | XML file (or use xml). |
| XML | `xml` | Text | No |  | Main; choice Load from = XML text | XML text (or use path). |
| Session | `session` | Text | No | `xml` | Main | Session name for later steps. |
| Leave out blank text | `drop_blank_text` | Yes/No | No | No | Main | Remove text that is only spaces and line breaks between elements, so nodes hold only their real text. |
| Agents this one runs may use it | `share` | Yes/No | No | No | More options | Agents started from this one with Run agent may use this session by its name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `xml` | Text | The loaded XML. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

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
| Namespaces | `namespaces` | Dictionary | No |  | Main | Prefix to namespace URI for prefixes in the XPath, e.g. {"inv": "urn:example:invoice"}; prefixes declared in the document work without this. Map a default namespace (xmlns="...") to a prefix of your choice to address it; a prefix with an empty URI, e.g. {"d": ""}, stands for the document's own default namespace. Example: `{"inv": "urn:example:invoice"}` |
| Session | `session` | Text | No |  | Main | Go through the nodes of this XML session (Start session) instead of a file or text. |

*Needs: filesystem.*
