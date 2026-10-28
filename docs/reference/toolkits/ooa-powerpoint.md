# PowerPoint

Toolkit `ooa.powerpoint`, version 1.0.0. Open or create .pptx presentations, add, copy and delete slides, edit text, pictures and tables, and save.

[All toolkits](README.md)

## Contents

- **Slides**: Add slide, Copy slide, Delete slide, Get slide count
- **Presentation**: Close, Create presentation, Open presentation, Save, Save as
- **Text**: Extract text from a file, Find and replace text, Format text, Insert text, Read text
- **Pictures**: Find and replace picture, Insert picture
- **Tables**: Insert table

## Slides

### Add slide

`ooa.powerpoint/add_slide` (action)

Add a slide with a chosen layout, optionally filling its title and body, at the end or at a given position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Layout | `layout` | Text | No | `title_and_content` | Main | Slide layout from the first slide master that has it. One of: `title`, `title_and_content`, `title_only`, `section_header`, `blank`, `two_content`, `comparison`, `content_with_caption`, `picture_with_caption` |
| Position | `position` | Number | No |  | Main; choice Position = At a slide number | 1-based position for the new slide; at the end when empty. |
| Title | `title` | Text | No |  | More options | Text for the title placeholder. |
| Body | `body` | Text | No |  | More options | Text for the body or subtitle placeholder; one line per bullet. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `slide` | Number | 1-based number of the new slide. |

*Time limit 60 s.*

### Copy slide

`ooa.powerpoint/copy_slide` (action)

Duplicate a slide, including its text, pictures and tables, from this or another open presentation, and place the copy at a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Slide | `slide` | Number | Yes |  | Main | 1-based number of the slide to copy. |
| Position | `position` | Number | No |  | Main; choice Put the copy = At a slide number | 1-based position for the copy; right after the original (or at the end, for another presentation) when empty. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session the copy goes into. |
| Source session | `source_session` | Text | No |  | More options | Session of another open presentation to copy the slide from; this presentation when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `slide` | Number | 1-based number of the copy. |

*Time limit 60 s.*

### Delete slide

`ooa.powerpoint/delete_slide` (action)

Remove one slide, or several slides in a row, from the presentation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Slide | `slide` | Number | Yes |  | Main | 1-based slide number. |
| Count | `count` | Number | No | `1` | More options | How many slides to delete, starting at slide. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

*Time limit 60 s.*

### Get slide count

`ooa.powerpoint/slide_count` (action)

Count the slides in the presentation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `powerpoint` | Main | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of slides. |

*Time limit 60 s; safe to repeat.*

## Presentation

### Close

`ooa.powerpoint/close` (action)

Close the presentation session, saving the changes first when asked.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Save | `save` | Yes/No | No | No | Main | Save the changes to the opened file before closing. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

*Time limit 60 s; needs: filesystem.*

### Create presentation

`ooa.powerpoint/create_presentation` (action)

Create a new .pptx file on disk, empty or as a copy of a template presentation, optionally password-protected, and open it as a named session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Path of the new presentation. |
| Password | `password` | Credential (vault) | No |  | Main; tick Require a password to open the file | Encrypt the new file with this password; later saves keep it. |
| Edit password | `edit_password` | Credential (vault) | No |  | Main; tick Require a password to edit the file | Password to modify: PowerPoint asks for it, or offers to open the file read-only. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace a file that already exists at the path. |
| Template | `template` | File path | No |  | More options | Existing .pptx whose slide masters, layouts and slides the new file starts from; empty when empty. |
| Session | `session` | Text | No | `powerpoint` | More options | Session name used by later steps. |

*Time limit 60 s; needs: filesystem.*

### Open presentation

`ooa.powerpoint/open` (action)

Open a .pptx presentation, or create a new empty one, as a named session; can open password-protected files and open read-only.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Presentation path. |
| Read only | `read_only` | Yes/No | No | No | Main | Open for reading only: save over the opened file is refused, save_as to another file still works. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password of an encrypted presentation; saves keep the file encrypted with it. |
| Create | `create` | Yes/No | No | No | More options | Create the presentation when it does not exist. |
| Session | `session` | Text | No | `powerpoint` | More options | Session name used by later steps. |

*Time limit 60 s; needs: filesystem.*

### Save

`ooa.powerpoint/save` (action)

Save the presentation to the file it was opened from, or to another path; password-protected files stay protected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `powerpoint` | Main | Presentation session. |
| Path | `path` | File path | No |  | More options | Other path to write to; the opened file when empty. |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the saved file with this password; the password the presentation was opened with when empty. |

*Time limit 60 s; needs: filesystem.*

### Save as

`ooa.powerpoint/save_as` (action)

Save the presentation to a new .pptx file, optionally password-protected, and continue the session on that file; PDF and image export are not available.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Target file path. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file that already exists at the target path; false stops with PPT.EXISTS. |
| Format | `format` | Text | No | `pptx` | More options | File format; only pptx is supported. One of: `pptx` |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the new file with this password; later saves keep it. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

*Time limit 60 s; needs: filesystem.*

## Text

### Extract text from a file

`ooa.powerpoint/extract_text` (action)

Return the whole text of a .ppt or .pptx file without opening it as a session, optionally with the speaker notes; old .ppt files included.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | The .ppt or .pptx file. |
| Include notes | `include_notes` | Yes/No | No | No | Main | Also return the speaker notes. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The presentation's text, slide after slide. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Find and replace text

`ooa.powerpoint/find_replace_text` (action)

Replace text on every slide, including tables and grouped shapes, e.g. fill {{name}} placeholders; can ignore case, match whole words or change only the first or last occurrence.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Find | `find` | Text | Yes |  | Main | Text to find. Example: `{{customer}}` |
| Match case | `match_case` | Yes/No | No | Yes | Main | Match upper/lower case exactly. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Only match the text as a whole word, not inside a longer word. |
| Slide | `slide` | Number | No |  | Main; choice Look in = One slide | Only this 1-based slide; all slides when empty. |
| Replace with | `replace_with` | Text | No | (empty) | Main | Replacement text. |
| Occurrence | `occurrence` | Text | No | `all` | Main | Which occurrences to change, in slide order: all, the first or the last. One of: `all`, `first`, `last` |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of replacements. |

*Time limit 60 s.*

### Format text

`ooa.powerpoint/format_text` (action)

Apply bold, italic, underline, strikethrough, size, colour or font to occurrences of a text on the slides; inputs left empty are not changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to format wherever it appears. |
| Match case | `match_case` | Yes/No | No | Yes | Main | Match upper/lower case exactly. |
| Whole word | `whole_word` | Yes/No | No | No | Main | Only match the text as a whole word, not inside a longer word. |
| Slide | `slide` | Number | No |  | Main; choice Look in = One slide | Only this 1-based slide; all slides when empty. |
| Occurrence | `occurrence` | Text | No | `all` | Main | Which occurrences to change, in slide order: all, the first or the last. One of: `all`, `first`, `last` |
| Font | `font` | Text | No |  | Main | Font name, e.g. Calibri. |
| Size | `size` | Number | No |  | Main | Font size in points. |
| Color | `color` | Text | No |  | Main | Hex text colour. Example: `#C00000` |
| Bold | `bold` | Yes/No | No |  | Main | Bold on or off. |
| Italic | `italic` | Yes/No | No |  | Main | Italic on or off. |
| Underline | `underline` | Yes/No | No |  | Main | Underline on or off. |
| Strikethrough | `strikethrough` | Yes/No | No |  | More options | Strikethrough on or off. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of occurrences formatted. |

*Time limit 60 s.*

### Insert text

`ooa.powerpoint/insert_text` (action)

Put text into a slide's title or body placeholder, or add it as a new text box at a position given in points.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Slide | `slide` | Number | No |  | Main; choice Slide = A slide number | 1-based slide number; the last slide (usually the one just added) when empty. Range: 1 to  |
| Text | `text` | Text | Yes |  | Main | Text to insert; each line becomes a paragraph. |
| X | `x` | Number | No | `50` | Main | Text box left edge in points. |
| Y | `y` | Number | No | `50` | Main | Text box top edge in points. |
| Font | `font` | Text | No |  | Main; tick Format the text | Font name for the new text, e.g. Calibri. |
| Font size | `font_size` | Number | No |  | Main; tick Format the text | Font size in points for the new text. |
| Color | `color` | Text | No |  | Main; tick Format the text | Hex colour for the new text. Example: `#C00000` |
| Bold | `bold` | Yes/No | No |  | Main; tick Format the text | Bold on or off for the new text. |
| Italic | `italic` | Yes/No | No |  | Main; tick Format the text | Italic on or off for the new text. |
| Underline | `underline` | Yes/No | No |  | Main; tick Format the text | Underline on or off for the new text. |
| Placeholder | `placeholder` | Text | No | `none` | More options | title or body replaces that placeholder's text; none adds a new text box. One of: `none`, `title`, `body` |
| Width | `width` | Number | No | `400` | More options | Text box width in points. |
| Height | `height` | Number | No | `50` | More options | Text box height in points. |
| Alignment | `alignment` | Text | No |  | More options | Paragraph alignment of the new text; unchanged when empty. One of: `left`, `center`, `right`, `justify` |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

*Time limit 60 s.*

### Read text

`ooa.powerpoint/read_text` (action)

Return the text of every slide, one entry per slide in slide order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `powerpoint` | Main | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `slides` | List | Text of each slide; shapes are separated by line breaks. |
| `text` | Text | All slide texts joined, separated by blank lines. |

*Time limit 60 s; safe to repeat.*

## Pictures

### Find and replace picture

`ooa.powerpoint/find_replace_picture` (action)

Swap pictures, found by alternative text or by their order across the slides, for a new image file, keeping the position and, unless a new size is given, the size.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Alt text | `alt_text` | Text | No |  | Main; choice Find the picture by = Its alt text | Replace every picture whose alternative text equals this. |
| Occurrence | `occurrence` | Text | No | `all` | Main; choice Find the picture by = Its alt text | With alt_text: replace all matching pictures, or only the first or last one in slide order. One of: `all`, `first`, `last` |
| Index | `index` | Number | No |  | Main; choice Find the picture by = Its position | 1-based position of the picture counting across all slides; used when alt_text is empty. |
| Slide | `slide` | Number | No |  | Main; choice Look in = One slide | Only look on this 1-based slide (index then counts on that slide); all slides when empty. |
| Image | `image` | File path | Yes |  | Main | New image file. |
| New alt text | `new_alt_text` | Text | No |  | Main | New alternative text for the replaced pictures; kept when empty. |
| Width | `width` | Number | No |  | Main | New width in points; the current size is kept when width and height are empty. |
| Height | `height` | Number | No |  | Main | New height in points. |
| Keep aspect ratio | `keep_aspect_ratio` | Yes/No | No | Yes | Main | When only width or only height is given, scale the other side to the new image's proportions. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of pictures replaced. |

*Time limit 60 s; needs: filesystem.*

### Insert picture

`ooa.powerpoint/insert_picture` (action)

Place an image file on a slide at a position in points, optionally scaled to a width, a height or both.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Slide | `slide` | Number | No |  | Main; choice Slide = A slide number | 1-based slide number; the last slide (usually the one just added) when empty. Range: 1 to  |
| Image | `image` | File path | Yes |  | Main | Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF). |
| X | `x` | Number | No | `50` | Main | Left edge in points. |
| Y | `y` | Number | No | `50` | Main | Top edge in points. |
| Width | `width` | Number | No |  | Main | Width in points; the height keeps the aspect ratio. Original size when empty. |
| Height | `height` | Number | No |  | Main | Height in points; with width both are used as given, alone the width keeps the aspect ratio. |
| Alt text | `alt_text` | Text | No |  | More options | Alternative text, also used later by find_replace_picture. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `shape` | Text | Name of the new picture shape. |

*Time limit 60 s; needs: filesystem.*

## Tables

### Insert table

`ooa.powerpoint/insert_table` (action)

Add a table to a slide from a Data Table, with the column names as an optional bold header row, at a position and size in points.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Slide | `slide` | Number | No |  | Main; choice Slide = A slide number | 1-based slide number; the last slide (usually the one just added) when empty. Range: 1 to  |
| X | `x` | Number | No | `50` | Main | Left edge in points. |
| Y | `y` | Number | No | `120` | Main | Top edge in points. |
| Width | `width` | Number | No | `600` | Main | Total table width in points. |
| Row height | `row_height` | Number | No |  | Main | Height of each row in points; twice the font size when empty. |
| Column widths | `column_widths` | List | No |  | Main | Width of each column in points, in order; the total width is shared equally when empty. |
| Table | `table` | Table | Yes |  | Main | Data to insert. |
| Include header | `include_header` | Yes/No | No | Yes | Main | Put the column names in a bold first row. |
| Font size | `font_size` | Number | No | `14` | More options | Font size in points. |
| Session | `session` | Text | No | `powerpoint` | More options | Presentation session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `shape` | Text | Name of the new table shape. |

*Time limit 60 s.*
