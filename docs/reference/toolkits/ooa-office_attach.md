# Open Excel and Word files (needs Office)

Toolkit `ooa.office_attach`, version 1.0.0. Read, write and save an Excel workbook or Word document that is already open on the desktop, in the same window.

[All toolkits](README.md)

## Contents

- **Excel**: List open workbooks, Read range from open workbook, Save open workbook, Write to open workbook
- **Word**: Append text to open document, List open documents, Read open document, Replace text in open document, Save open document

## Excel

### List open workbooks

`ooa.office_attach/excel_list_open` (action)

List the workbooks open in the running Excel, with their full paths and sheets.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `workbooks` | List | Records with name, path, sheets (list) and saved (false when it has unsaved changes). |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Read range from open workbook

`ooa.office_attach/excel_read_range` (action)

Read a range, or the used area, of a sheet in a workbook that is open in Excel, including values not yet saved, as a Data Table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workbook | `workbook` | Text | Yes |  | Main | Name of the open workbook (Sales.xlsx) or its full path. Example: `Sales.xlsx` |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; the sheet shown in Excel when empty. |
| Range | `range` | Text | No |  | Main | Range to read; the sheet's used area when empty. Example: `A1:D20` |
| First row is the header | `header` | Yes/No | No | Yes | Main | The first row holds the column names. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | The cells; columns named from the first row when header is on. |
| `row_count` | Number | Number of data rows. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Save open workbook

`ooa.office_attach/excel_save` (action)

Save a workbook that is open in Excel, under its own name; it stays open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workbook | `workbook` | Text | Yes |  | Main | Name of the open workbook (Sales.xlsx) or its full path. Example: `Sales.xlsx` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Maximum time in seconds. |

*Time limit 60 s; needs: desktop.*

### Write to open workbook

`ooa.office_attach/excel_write_range` (action)

Write a value, a list of rows or a Data Table into a sheet of a workbook that is open in Excel, starting at a cell; the person sees it appear. Saving is a separate step.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Workbook | `workbook` | Text | Yes |  | Main | Name of the open workbook (Sales.xlsx) or its full path. Example: `Sales.xlsx` |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; the sheet shown in Excel when empty. |
| Start cell | `start_cell` | Text | No | `A1` | Main | Top-left cell to write to. |
| Values | `values` | Any | Yes |  | Main | One value, a list of rows (each a list of values) or a Data Table. |
| Include header | `include_header` | Yes/No | No | Yes | Main | For a Data Table: write its column names as the first row. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `range` | Text | Address of the cells written, e.g. B2:E10. |

*Time limit 60 s; needs: desktop.*

## Word

### Append text to open document

`ooa.office_attach/word_append_text` (action)

Add text at the end of a document that is open in Word, as a new paragraph by default. Saving is a separate step.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document | `document` | Text | Yes |  | Main | Name of the open document (Letter.docx) or its full path. Example: `Letter.docx` |
| Text | `text` | Text | Yes |  | Main | Text to add; line breaks start new paragraphs. |
| New paragraph | `new_paragraph` | Yes/No | No | Yes | Main | Start a new paragraph first. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time in seconds. |

*Time limit 60 s; needs: desktop.*

### List open documents

`ooa.office_attach/word_list_open` (action)

List the documents open in the running Word, with their full paths.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `documents` | List | Records with name, path and saved (false when it has unsaved changes). |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Read open document

`ooa.office_attach/word_read_text` (action)

Read the text of a document that is open in Word, including changes not yet saved.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document | `document` | Text | Yes |  | Main | Name of the open document (Letter.docx) or its full path. Example: `Letter.docx` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The document's text, paragraphs on separate lines. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Replace text in open document

`ooa.office_attach/word_replace_text` (action)

Replace every occurrence of a text in a document that is open in Word, e.g. a {{name}} placeholder; the person sees it change. Saving is a separate step.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document | `document` | Text | Yes |  | Main | Name of the open document (Letter.docx) or its full path. Example: `Letter.docx` |
| Find | `find` | Text | Yes |  | Main | Text to find (up to 255 characters). Example: `{{name}}` |
| Replace with | `replace_with` | Text | No | (empty) | Main | New text (up to 255 characters); empty removes it. |
| Match case | `match_case` | Yes/No | No | No | More options | Match upper and lower case exactly. |
| Whole word | `whole_word` | Yes/No | No | No | More options | Only whole words. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of occurrences replaced. |

*Time limit 60 s; needs: desktop.*

### Save open document

`ooa.office_attach/word_save` (action)

Save a document that is open in Word, under its own name; it stays open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document | `document` | Text | Yes |  | Main | Name of the open document (Letter.docx) or its full path. Example: `Letter.docx` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Maximum time in seconds. |

*Time limit 60 s; needs: desktop.*
