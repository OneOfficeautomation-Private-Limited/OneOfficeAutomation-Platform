# Word (no Office needed)

Toolkit `ooa.word`, version 1.0.0. Open or create .docx documents, read text and tables, replace text, add paragraphs and tables, save.

[All toolkits](README.md)

## Contents

- **Text**: Add paragraph, Extract text from a file, Format text, Insert text, Read text, Replace text
- **Tables**: Insert table, Read tables
- **Document**: Close, Create document, Open document, Save, Save as
- **Pictures**: Find and replace picture, Insert picture

## Text

### Add paragraph

`ooa.word/add_paragraph` (action)

Append a paragraph, optionally bold or with a style such as Heading1.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Paragraph text. |
| Bold | `bold` | Yes/No | No | No | Main | Bold text. |
| Style | `style` | Text | No |  | Main | Style id from the document, e.g. Heading1. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s.*

### Extract text from a file

`ooa.word/extract_text` (action)

Return the whole text of a .doc, .docx or .rtf file without opening it as a session; old .doc and .rtf files included.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | The .doc, .docx or .rtf file. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The document's text. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Format text

`ooa.word/format_text` (action)

Apply bold, italic, underline, strikethrough, highlight, font, size or colour to occurrences of a text; inputs left empty are not changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to format wherever it appears. |
| Match case | `match_case` | Yes/No | No | Yes | Main | Match upper/lower case exactly. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Only match the text as a whole word, not inside a longer word. |
| From paragraph | `from_paragraph` | Number | No |  | Main; choice Look in = A paragraph range | 1-based first body paragraph to search; the whole document (with tables, headers and footers) when both are empty. Range: 1 to  |
| To paragraph | `to_paragraph` | Number | No |  | Main; choice Look in = A paragraph range | 1-based last body paragraph to search, included; to the end when empty. Range: 1 to  |
| Occurrence | `occurrence` | Text | No | `all` | Main | Which occurrences to change, in document order: all, the first or the last. One of: `all`, `first`, `last` |
| Font | `font` | Text | No |  | Main | Font name, e.g. Calibri. |
| Color | `color` | Text | No |  | Main | Hex text colour. Example: `#C00000` |
| Size | `size` | Number | No |  | Main | Font size in points. Range: 1 to 1638 |
| Bold | `bold` | Yes/No | No |  | Main | Bold on or off. |
| Italic | `italic` | Yes/No | No |  | Main | Italic on or off. |
| Underline | `underline` | Yes/No | No |  | Main | Single underline on or off. |
| Highlight | `highlight` | Text | No |  | More options | Highlighter colour behind the text, or none to remove it. One of: `yellow`, `green`, `cyan`, `magenta`, `blue`, `red`, `darkBlue`, `darkCyan`, `darkGreen`, `darkMagenta`, `darkRed`, `darkYellow`, `darkGray`, `lightGray`, `black`, `white`, `none` |
| Strikethrough | `strikethrough` | Yes/No | No |  | More options | Strikethrough on or off. |
| Session | `session` | Text | No | `word` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of occurrences formatted. |

*Time limit 60 s.*

### Insert text

`ooa.word/insert_text` (action)

Insert text at the start or end of the document, or just before or after a marker text or a bookmark, optionally with its own font formatting.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Position | `position` | Text | No | `end` | Main | Where to insert: end, start of the document, after_text, before_text or bookmark. One of: `end`, `start`, `after_text`, `before_text`, `bookmark` |
| Anchor | `anchor` | Text | No |  | Main | Marker text or bookmark name the position refers to. |
| Text | `text` | Text | Yes |  | Main | Text to insert; line breaks are kept. |
| New paragraph | `new_paragraph` | Yes/No | No | Yes | Main | Put the text in its own paragraph; false adds it inside the existing paragraph. |
| Font | `font` | Text | No |  | Main; tick Format the text | Font name for the inserted text, e.g. Calibri. |
| Color | `color` | Text | No |  | Main; tick Format the text | Hex colour for the inserted text. Example: `#C00000` |
| Size | `size` | Number | No |  | Main; tick Format the text | Font size in points for the inserted text. |
| Bold | `bold` | Yes/No | No |  | Main; tick Format the text | Bold on or off for the inserted text; unchanged when empty. |
| Italic | `italic` | Yes/No | No |  | Main; tick Format the text | Italic on or off for the inserted text. |
| Underline | `underline` | Yes/No | No |  | Main; tick Format the text | Single underline on or off for the inserted text. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s.*

### Read text

`ooa.word/read_text` (action)

Return the paragraph text, one paragraph per line, of the whole body, a range of paragraphs, the text inside a bookmark or one page, optionally with tables, headers and footers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| From paragraph | `from_paragraph` | Number | No |  | Main; choice Read = A paragraph range | 1-based first body paragraph to read; the first when empty. |
| To paragraph | `to_paragraph` | Number | No |  | Main; choice Read = A paragraph range | 1-based last body paragraph to read; the last when empty. |
| Bookmark | `bookmark` | Text | No |  | Main; choice Read = A bookmark | Read only the text between the start and end of this bookmark (tables inside it included); overrides the paragraph range and page. |
| Page | `page` | Number | No |  | Main; choice Read = A page | Read only this 1-based page, counted from page breaks, 'page break before' paragraphs and section breaks stored in the file (pages Word fills up on its own are not counted); overrides the paragraph range. Range: 1 to  |
| Include tables | `include_tables` | Yes/No | No | No | Main | Also return the text of table cells, in document order, one cell per line. |
| Include headers footers | `include_headers_footers` | Yes/No | No | No | Main | Also return the text of page headers (before the body) and footers (after it). |
| Session | `session` | Text | No | `word` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Document text. |
| `paragraphs` | List | Each body paragraph's text. |

*Time limit 60 s; safe to repeat.*

### Replace text

`ooa.word/replace_text` (action)

Replace text everywhere (body, tables, headers, footers), e.g. fill {{customer}} placeholders in a template; can ignore case, match whole words or change only the first or last occurrence.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Find | `find` | Text | Yes |  | Main | Text to find. Example: `{{customer}}` |
| Match case | `match_case` | Yes/No | No | Yes | Main | Match upper/lower case exactly. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Only match the text as a whole word, not inside a longer word. |
| From paragraph | `from_paragraph` | Number | No |  | Main; choice Look in = A paragraph range | 1-based first body paragraph to search; the whole document (with tables, headers and footers) when both are empty. Range: 1 to  |
| To paragraph | `to_paragraph` | Number | No |  | Main; choice Look in = A paragraph range | 1-based last body paragraph to search, included; to the end when empty. Range: 1 to  |
| Replace with | `replace_with` | Text | No | (empty) | Main | Replacement text. |
| Occurrence | `occurrence` | Text | No | `all` | Main | Which occurrences to change, in document order: all, the first or the last. One of: `all`, `first`, `last` |
| Session | `session` | Text | No | `word` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of replacements. |

*Time limit 60 s.*

## Tables

### Insert table

`ooa.word/add_table` (action)

Insert a table built from a Data Table (header row first) at the end, after a marker text or at a bookmark, optionally with a table style.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| After text | `after_text` | Text | No |  | Main; choice Insert = After a text | Insert after the paragraph containing this text; at the end when empty. |
| Bookmark | `bookmark` | Text | No |  | Main; choice Insert = After a bookmark | Insert after the paragraph holding this bookmark; used when after_text is empty. |
| Table | `table` | Table | Yes |  | Main | Data to insert. |
| Include header | `include_header` | Yes/No | No | Yes | Main | Put the column names in a bold first row. |
| Style | `style` | Text | No |  | More options | Table style id defined in the document, e.g. TableGrid. Example: `TableGrid` |
| Title | `title` | Text | No |  | More options; tick Give the table alternative text | Alternative-text title of the table, read by screen readers (Word: Table Properties > Alt Text > Title). |
| Alt description | `alt_description` | Text | No |  | More options; tick Give the table alternative text | Alternative-text description of the table (Word: Table Properties > Alt Text > Description). |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s.*

### Read tables

`ooa.word/read_tables` (action)

Read every table in the document, one table by position or one table by its title, optionally only some of its rows and columns; the first row read can become the column names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Index | `index` | Number | No |  | Main; choice Read = One table | 1-based position of the one table to read; all tables when empty. |
| Title | `title` | Text | No |  | Main; choice Read = A table by title | Read the one table with this title: the table's alternative-text title, or a Caption-style paragraph just above or below it that contains this text (case is ignored). Used instead of index. |
| First row is the header | `header` | Yes/No | No | Yes | Main | The first row read holds the column names; false names the columns Column1, Column2 and keeps the first row as data. |
| From row | `from_row` | Number | No |  | Main; tick Only some rows or columns | 1-based first row of the table to read, counting the header row; the first when empty. Range: 1 to  |
| To row | `to_row` | Number | No |  | Main; tick Only some rows or columns | 1-based last row to read, included; the last when empty. Range: 1 to  |
| From column | `from_column` | Number | No |  | Main; tick Only some rows or columns | 1-based first column to read; the first when empty. Range: 1 to  |
| To column | `to_column` | Number | No |  | Main; tick Only some rows or columns | 1-based last column to read, included; the last when empty. Range: 1 to  |
| Session | `session` | Text | No | `word` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tables` | List | List of tables. |
| `count` | Number | Number of tables in the document. |
| `table` | Table | The table chosen by index or title; the first table when both are empty, or empty when there is none. |

*Time limit 60 s; safe to repeat.*

## Document

### Close

`ooa.word/close` (action)

Close the document, saving the changes first when asked.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Save | `save` | Yes/No | No | No | Main | Save the changes to the opened file before closing. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s; needs: filesystem.*

### Create document

`ooa.word/create_document` (action)

Create a new .docx file on disk, empty or as a copy of a template document, optionally password-protected, and open it as a named session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Path of the new document. |
| Password | `password` | Credential (vault) | No |  | Main; tick Require a password to open the file | Encrypt the new file with this password; later saves keep it. |
| Edit password | `edit_password` | Credential (vault) | No |  | Main; tick Require a password to edit the file | Restrict editing with this password: Word opens the document read-only until the password is given (Review > Restrict Editing > Stop Protection). |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace a file that already exists at the path. |
| Template | `template` | File path | No |  | More options | Existing .docx whose content and styles the new document starts from; empty document when empty. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s; needs: filesystem.*

### Open document

`ooa.word/open` (action)

Open a .docx document, or create a new one, as a named session; can open password-protected files and open read-only.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Document path. |
| Read only | `read_only` | Yes/No | No | No | Main | Open for reading only: save over the opened file is refused, save_as to another file still works. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password of an encrypted document; saves keep the file encrypted with it. |
| Create | `create` | Yes/No | No | No | More options | Create the document when it does not exist. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s; needs: filesystem.*

### Save

`ooa.word/save` (action)

Save the document, optionally to a new path; password-protected documents stay protected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `word` | Main | Session name. |
| Path | `path` | File path | No |  | More options | Save-as path; the opened file when empty. |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the saved file with this password; the password the document was opened with when empty. |

*Time limit 60 s; needs: filesystem.*

### Save as

`ooa.word/save_as` (action)

Save the document to a new .docx file (optionally password-protected) and continue the session on it, or export its text as a .txt file; PDF output is not available in this toolkit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Target file path. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file that already exists at the target path; false stops with WORD.EXISTS. |
| Format | `format` | Text | No | `docx` | Main | docx keeps the document; txt writes the plain text (body, tables and headers) and the session stays on the docx file. One of: `docx`, `txt` |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the new .docx file with this password; later saves keep it. |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s; needs: filesystem.*

## Pictures

### Find and replace picture

`ooa.word/find_replace_picture` (action)

Swap an existing picture, found by its alternative text or its position in the document, for a new image file, optionally resizing it and changing its alternative text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Alt text | `alt_text` | Text | No |  | Main; choice Find the picture by = Its alt text | Replace every picture whose alternative text equals this. |
| Occurrence | `occurrence` | Text | No | `all` | Main; choice Find the picture by = Its alt text | With alt_text: replace all matching pictures, or only the first or last one in document order. One of: `all`, `first`, `last` |
| Index | `index` | Number | No |  | Main; choice Find the picture by = Its position | 1-based position of the picture in the document; used when alt_text is empty. |
| Image | `image` | File path | Yes |  | Main | New image file. |
| New alt text | `new_alt_text` | Text | No |  | Main | New alternative text for the replaced pictures; kept when empty. |
| Width | `width` | Number | No |  | Main | New width in points; kept when empty (with only height, the width follows the new image's ratio). |
| Height | `height` | Number | No |  | Main | New height in points; kept when empty (with only width, the height follows the new image's ratio). |
| Session | `session` | Text | No | `word` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of pictures replaced. |

*Time limit 60 s; needs: filesystem.*

### Insert picture

`ooa.word/insert_picture` (action)

Insert an image file at the start or end of the document, after a marker text or at a bookmark, optionally sized and aligned.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Image | `image` | File path | Yes |  | Main | Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF). |
| Alt text | `alt_text` | Text | No |  | Main | Alternative text, also used later by find_replace_picture. |
| Width | `width` | Number | No |  | Main | Width in points (72 per inch); the height keeps the aspect ratio. Original size when empty. |
| Height | `height` | Number | No |  | Main | Height in points; with width both are used as given, alone the width keeps the aspect ratio. |
| Position | `position` | Text | No | `end` | Main; choice Place it = At the start or end | Where the picture goes when after_text and bookmark are empty: end or start of the document. One of: `end`, `start` |
| After text | `after_text` | Text | No |  | Main; choice Place it = After a text | Put the picture in a new paragraph after the paragraph with this text; at the end when empty. |
| Bookmark | `bookmark` | Text | No |  | Main; choice Place it = After a bookmark | Put the picture in a new paragraph after the paragraph holding this bookmark. |
| Alignment | `alignment` | Text | No |  | More options | Horizontal alignment of the picture's paragraph; unchanged when empty. One of: `left`, `center`, `right` |
| Session | `session` | Text | No | `word` | More options | Session name. |

*Time limit 60 s; needs: filesystem.*
