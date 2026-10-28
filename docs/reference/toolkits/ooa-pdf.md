# PDF

Toolkit `ooa.pdf`, version 1.0.0. Extract text, merge, split, read and fill forms, encrypt, decrypt and convert PDF pages to images.

[All toolkits](README.md)

## Contents

- **Security**: Decrypt PDF, Encrypt PDF
- **Text**: Extract text from area, Extract text
- **Document**: Extract images, Get document properties, Merge PDFs, Get page count, Split PDF, Convert to images
- **Forms**: Fill form fields, Read form fields

## Security

### Decrypt PDF

`ooa.pdf/decrypt` (action)

Remove password protection (needs the open or owner password).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Protected PDF. |
| Password | `password` | Credential (vault) | Yes |  | Main | Password from the vault. |
| Target | `target` | File path | Yes |  | Main | Unprotected PDF to create. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace the target when it already exists; false stops with PDF.EXISTS. |

*Time limit 60 s; needs: filesystem.*

### Encrypt PDF

`ooa.pdf/encrypt` (action)

Protect a PDF with an open password and an owner password, choosing the AES key length and what readers may do (print, copy, edit, annotate, fill forms, assemble).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| User password | `user_password` | Credential (vault) | Yes |  | Main | Password needed to open the PDF. |
| Owner password | `owner_password` | Credential (vault) | Yes |  | Main | Password needed to change permissions. |
| Allow printing | `allow_printing` | Yes/No | No | Yes | Main | Allow printing. |
| Allow modify | `allow_modify` | Yes/No | No | Yes | Main | Allow changing the content. |
| Allow copy | `allow_copy` | Yes/No | No | Yes | Main | Allow copying text and images. |
| Allow fill forms | `allow_fill_forms` | Yes/No | No | Yes | Main | Allow filling in form fields. |
| Allow assembly | `allow_assembly` | Yes/No | No | Yes | Main | Allow inserting, rotating and deleting pages. |
| Allow annotations | `allow_annotations` | Yes/No | No | Yes | Main | Allow adding comments and annotations. |
| Allow accessibility | `allow_accessibility` | Yes/No | No | Yes | Main | Allow screen readers to extract the text. |
| Key length | `key_length` | Number | No | `256` | Main | AES key length in bits: 256 (recommended) or 128 for older readers. One of: `128`, `256` |
| Target | `target` | File path | Yes |  | Main | Encrypted PDF to create. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace the target when it already exists; false stops with PDF.EXISTS. |

*Time limit 60 s; needs: filesystem.*

## Text

### Extract text from area

`ooa.pdf/extract_area_text` (action)

Read the text inside a rectangle of one page, e.g. an invoice number box that is not a form field.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Page | `page` | Number | No | `1` | Main | 1-based page number. |
| X | `x` | Number | Yes |  | Main | Left edge of the area in points (72 per inch) from the left of the page. |
| Y | `y` | Number | Yes |  | Main | Top edge of the area in points from the top of the page. |
| Width | `width` | Number | Yes |  | Main | Width of the area in points. |
| Height | `height` | Number | Yes |  | Main | Height of the area in points. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Text found inside the rectangle, trimmed. |

*Time limit 300 s; safe to repeat; needs: filesystem.*

### Extract text

`ooa.pdf/extract_text` (action)

Extract the text of a PDF, of a page range or of chosen pages, as plain or layout-ordered text, optionally into a .txt file. Scanned PDFs (images) need OCR instead.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Mode | `mode` | Text | No | `structured` | Main | structured orders text by its position on the page, keeping columns and tables readable; plain keeps the order stored in the file, like copy and paste. One of: `structured`, `plain` |
| From page | `from_page` | Number | No | `1` | Main; choice Pages = A page range | First page (1-based). |
| To page | `to_page` | Number | No |  | Main; choice Pages = A page range | Last page; the end when empty. |
| Pages | `pages` | Text | No |  | Main; choice Pages = Listed pages | Exact pages to read, e.g. 1,3,5-7, in that order; overrides from_page and to_page. Example: `1,3,5-7` |
| Output file | `output_file` | File path | No |  | Main; tick Save the text to a file | Also write the text to this .txt file (UTF-8). |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main; tick Save the text to a file | Replace output_file when it already exists; false stops with PDF.EXISTS. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Extracted text. |
| `page_count` | Number | Total pages in the document. |

*Time limit 300 s; safe to repeat; needs: filesystem.*

## Document

### Extract images

`ooa.pdf/extract_images` (action)

Save the pictures embedded in a PDF (photos, logos, scanned pages), from every page or chosen pages, as image files in a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Pages | `pages` | Text | No |  | Main; tick Only some pages | Only pages listed here, e.g. 1,3,5-7; every page when empty. Example: `1,3,5-7` |
| Format | `format` | Text | No | `png` | Main | Image file format. One of: `png`, `jpg`, `bmp`, `gif`, `tiff` |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder for the images. |
| File prefix | `file_prefix` | Text | No |  | Main | Start of each image's file name, followed by _page1_img1 and so on; the PDF's name when empty. |
| Color | `color` | Text | No | `color` | Main | color keeps the colours; gray saves greyscale copies. One of: `color`, `gray` |
| Minimum size | `min_size` | Number | No | `1` | More options | Skip images narrower or lower than this many pixels (e.g. 32 to skip icons). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Created image files. |
| `count` | Number | Number of images saved. |

*Time limit 600 s; needs: filesystem.*

### Get document properties

`ooa.pdf/get_properties` (action)

Return the title, author, subject, keywords, creator, producer, dates, page count, PDF version and encryption of a PDF.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Include custom | `include_custom` | Yes/No | No | No | More options | Also return custom document properties (any other keys in the document information) under custom, plus file_name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `properties` | Dictionary | title, author, subject, keywords, creator, producer, created, modified, page_count, pdf_version, encrypted, and with include_custom also file_name and custom. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Merge PDFs

`ooa.pdf/merge` (action)

Combine several PDFs (a list, or every PDF in a folder), or chosen pages of each, in order, into one file; protected sources can be opened with passwords.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Files | `files` | List | No |  | Main; choice Files to merge = Listed files | PDF paths in the order to merge. Shown when: `{"folder": [""]}` |
| Folder | `folder` | File path | No |  | Main; choice Files to merge = Every PDF in a folder | Merge every PDF in this folder instead of a list, sorted by name (or by sort_by). Shown when: `{"files": [""]}` |
| Pattern | `pattern` | Text | No | `*.pdf` | Main; choice Files to merge = Every PDF in a folder | Which files of the folder, e.g. invoice-*.pdf. Shown when: `{"folder": ["!", ""]}` |
| Sort by | `sort_by` | Text | No | `name` | Main; choice Files to merge = Every PDF in a folder | Order of the folder's files: by name or by last change (oldest first). One of: `name`, `modified`; Shown when: `{"folder": ["!", ""]}` |
| Target | `target` | File path | Yes |  | Main | Merged PDF to create. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace the target when it already exists; false stops with PDF.EXISTS. |
| Pages | `pages` | List | No |  | More options | Pages to take from each file, in the same order as files, e.g. ["1-3", "", "5,2"]; an empty entry takes every page. |
| Passwords | `passwords` | List | No |  | More options | Passwords (Credentials) for protected files, in the same order as files; empty entries for unprotected ones. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `page_count` | Number | Pages in the merged file. |
| `files` | List | The PDFs that were merged, in order. |

*Time limit 600 s; needs: filesystem.*

### Get page count

`ooa.pdf/page_count` (action)

Return the number of pages.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of pages. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Split PDF

`ooa.pdf/split` (action)

Split a PDF into files of N pages each, into one file per page range, into one file holding chosen pages, at blank separator pages, or at its bookmarks.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Mode | `mode` | Text | No | `pages_per_file` | Main | pages_per_file cuts every N pages; ranges makes one file per comma-separated range in pages; selected_pages puts all pages listed in pages into one file; blank_page starts a new file after each blank page (blank pages are left out); bookmarks starts a new file at each bookmark down to bookmark_level. One of: `pages_per_file`, `ranges`, `selected_pages`, `blank_page`, `bookmarks` |
| Pages per file | `pages_per_file` | Number | No | `1` | Main | Pages in each part, for mode pages_per_file. |
| Pages | `pages` | Text | No |  | Main | Page ranges for modes ranges and selected_pages. Example: `1-3,4,5-9` |
| Bookmark level | `bookmark_level` | Number | No | `1` | Main | For mode bookmarks: the deepest bookmark level that starts a new file (1 = top-level bookmarks only, 2 = also their sub-bookmarks). |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder for the parts. |
| File prefix | `file_prefix` | Text | No |  | Main | Start of each part's file name, followed by _part1, _part2 and so on; the source file name when empty. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace parts that already exist; false stops with PDF.EXISTS. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Created files. |

*Time limit 600 s; needs: filesystem.*

### Convert to images

`ooa.pdf/to_images` (action)

Save each page, or chosen pages, as an image (PNG, JPEG, BMP, GIF or TIFF) in colour, grey or black and white, for OCR or previews.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF file. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password for protected PDFs. |
| Pages | `pages` | Text | No |  | Main; tick Only some pages | Pages to convert, e.g. 1,3,5-7; every page when empty. Example: `1,3,5-7` |
| Format | `format` | Text | No | `png` | Main | Image file format. One of: `png`, `jpg`, `bmp`, `gif`, `tiff` |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder for the images. |
| Resolution (DPI) | `dpi` | Number | No | `150` | Main | Resolution; 300 for OCR. |
| Color | `color` | Text | No | `color` | Main | color keeps the colours; gray gives greyscale; black_white gives 1-bit images, often best for OCR. One of: `color`, `gray`, `black_white` |
| File prefix | `file_prefix` | Text | No |  | More options | Start of each image's file name, followed by _page1 and so on; the PDF's name when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Created images. |

*Time limit 600 s; needs: filesystem.*

## Forms

### Fill form fields

`ooa.pdf/fill_form` (action)

Fill form fields by name and save a new PDF; optionally flatten so values can no longer be edited.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF form. |
| Password | `password` | Credential (vault) | No |  | Main; tick The form has a password | Password for protected PDFs; the filled copy is saved without protection. |
| Values | `values` | Dictionary | Yes |  | Main | Field name to value. |
| Target | `target` | File path | Yes |  | Main | Filled PDF to create. |
| Flatten | `flatten` | Yes/No | No | No | Main | Make the form read-only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `filled` | Number | Number of fields filled. |

*Time limit 60 s; needs: filesystem.*

### Read form fields

`ooa.pdf/read_form` (action)

Read the names and values of all form fields, or only of the fields named.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | PDF form. |
| Password | `password` | Credential (vault) | No |  | Main; tick The form has a password | Password for protected PDFs. |
| Field names | `field_names` | List | No |  | Main; tick Read only some fields | Read only these fields, in this order; every field when empty. A name the form does not have stops the step. Example: `["customer", "total"]` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Dictionary | Field name to value. |

*Time limit 60 s; safe to repeat; needs: filesystem.*
