# File

Toolkit `ooa.file`, version 1.0.0. Read, write, copy, move and delete files.

[All toolkits](README.md)

## Contents

- **Files**: Copy, Create shortcut, Create symbolic link, Delete, Get file info, Move or rename, Print files, Read text, Read variables from file, Wait for file, Write text
- **Paths**: Get file name, Get parent folder
- **Folders**: List files
- **Archives**: Unzip, Zip
- **Conditions**: File date is, File exists, File has extension, File size is
- **Loop types**: Each file in folder

## Files

### Copy

`ooa.file/copy` (action)

Copy a file, or every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | File path | Yes |  | Main | Source file, or a folder when pattern is given. |
| Target | `target` | File path | Yes |  | Main | Target file, or a folder when pattern is given. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace an existing target. |
| Pattern | `pattern` | Text | No |  | Main; tick Copy several files by name pattern | Copy every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too. Example: `*.pdf` |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main; tick Copy several files by name pattern | With a pattern, also take matching files in sub-folders, keeping their folders under the target. |
| Minimum size (bytes) | `min_size_bytes` | Number | No |  | Main; tick Filter by size | With a pattern, only files at least this large. Range: 0 to  |
| Maximum size (bytes) | `max_size_bytes` | Number | No |  | Main; tick Filter by size | With a pattern, only files at most this large. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; tick Filter by date | With a pattern, only files last changed after this date and time (local time). |
| Modified before | `modified_before` | DateTime | No |  | Main; tick Filter by date | With a pattern, only files last changed before this date and time (local time). |
| Keep both | `keep_both` | Yes/No | No | No | More options | When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping. Shown when: `{"overwrite": ["false"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Path of the copy (the target folder when copying several files). |
| `count` | Number | Number of files copied. |
| `files` | List | Paths of the copies. |

*Time limit 60 s; needs: filesystem.*

### Create shortcut

`ooa.file/create_shortcut` (action)

Create a shortcut that opens a file: a .lnk file on Windows, a .desktop file or symbolic link elsewhere.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File the shortcut opens. |
| Shortcut | `shortcut` | File path | Yes |  | Main | Shortcut to create; .lnk is added on Windows when missing. Example: `C:/Users/Public/Desktop/Report.lnk` |
| Arguments | `arguments` | Text | No |  | More options | Command-line arguments passed when the shortcut is opened. |
| Working folder | `working_folder` | File path | No |  | More options | Folder the program starts in. |
| Comment | `comment` | Text | No |  | More options | Tooltip text shown for the shortcut. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace an existing shortcut. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Created shortcut. |

*Time limit 60 s; needs: filesystem.*

### Create symbolic link

`ooa.file/create_symbolic_link` (action)

Create a symbolic link that points to a file; on Windows this needs Developer Mode or administrator rights.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File the link points to. |
| Link | `link` | File path | Yes |  | Main | Link to create. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace an existing file link (never a real folder). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Created link. |

*Time limit 60 s; needs: filesystem.*

### Delete

`ooa.file/delete` (action)

Delete a file if it exists, or every file in a folder that matches a pattern and optional size and age limits.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path, or a folder when pattern is given. |
| Pattern | `pattern` | Text | No |  | Main; tick Delete several files by name pattern | Delete the files in the folder at path that match this glob pattern. Example: `*.tmp` |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main; tick Delete several files by name pattern | With pattern, also delete matching files in sub-folders. |
| Minimum size (bytes) | `min_size_bytes` | Number | No |  | Main; tick Filter by size | With pattern, only files at least this large. Range: 0 to  |
| Maximum size (bytes) | `max_size_bytes` | Number | No |  | Main; tick Filter by size | With pattern, only files at most this large. Range: 0 to  |
| Older than (days) | `older_than_days` | Number | No |  | Main; tick Filter by date | With pattern, only files last changed more than this many days ago. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; tick Filter by date | With pattern, only files last changed after this date and time (local time). Shown when: `{"pattern": ["!", ""]}` |
| Modified before | `modified_before` | DateTime | No |  | Main; tick Filter by date | With pattern, only files last changed before this date and time (local time). Shown when: `{"pattern": ["!", ""]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | Number | Number of files deleted. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Get file info

`ooa.file/get_info` (action)

Return a file's name, extension, folder, size and last modified time.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | name, extension, folder, size_bytes, modified, exists. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Move or rename

`ooa.file/move` (action)

Move or rename a file, or move every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | File path | Yes |  | Main | Source file, or a folder when pattern is given. |
| Target | `target` | File path | Yes |  | Main | New path or name, or a folder when pattern is given. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace an existing target. |
| Keep both | `keep_both` | Yes/No | No | No | More options | When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping. Shown when: `{"overwrite": ["false"]}` |
| Pattern | `pattern` | Text | No |  | More options; tick Move several files by name pattern | Move every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too. Example: `*.pdf` |
| Include subfolders | `include_subfolders` | Yes/No | No | No | More options; tick Move several files by name pattern | With a pattern, also take matching files in sub-folders, keeping their folders under the target. |
| Minimum size (bytes) | `min_size_bytes` | Number | No |  | More options; tick Filter by size | With a pattern, only files at least this large. Range: 0 to  |
| Maximum size (bytes) | `max_size_bytes` | Number | No |  | More options; tick Filter by size | With a pattern, only files at most this large. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | More options; tick Filter by date | With a pattern, only files last changed after this date and time (local time). |
| Modified before | `modified_before` | DateTime | No |  | More options; tick Filter by date | With a pattern, only files last changed before this date and time (local time). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | New path of the file (the target folder when moving several files). |
| `count` | Number | Number of files moved. |
| `files` | List | New paths of the files. |

*Time limit 60 s; needs: filesystem.*

### Print files

`ooa.file/print_files` (action)

Print several files, in order, on a named printer or with each file's default application.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Files | `files` | List | No |  | Main; choice Print = Listed files | File paths to print (or use folder). Shown when: `{"folder": [""]}` |
| Folder | `folder` | File path | No |  | Main; choice Print = Files in a folder | Print the files in this folder that match pattern, after any files in the list. Shown when: `{"files": [""]}` |
| Pattern | `pattern` | Text | No | `*` | Main; choice Print = Files in a folder | With folder, glob pattern of the files to print. Example: `*.pdf`; Shown when: `{"files": [""]}` |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main; choice Print = Files in a folder | With folder, also print matching files in sub-folders. Shown when: `{"files": [""]}` |
| Modified within (days) | `modified_within_days` | Number | No |  | Main; choice Print = Files in a folder | With folder, only files changed in the last this many days. Range: 0 to ; Shown when: `{"files": [""]}` |
| Printer | `printer` | Text | No |  | Main | Printer name; the default printer when empty. |
| Copies | `copies` | Number | No | `1` | Main | Copies of each file. Range: 1 to 999 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `printed` | Number | Number of files sent to the printer. |

*Time limit 600 s; needs: filesystem.*

### Read text

`ooa.file/read_text` (action)

Read a text file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding, e.g. utf-8, utf-16le, windows-1252; auto reads a byte order mark, else takes UTF-8 when it fits, else windows-1252. Example: `auto` |
| Trim | `trim` | Text | No | `none` | More options | Remove spaces and line breaks at the start, the end or both ends of the text. One of: `none`, `leading`, `trailing`, `both` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | File content. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Read variables from file

`ooa.file/read_variables` (action)

Read name=value lines (a settings or .properties-style file) into a Dictionary; blank lines and lines starting with # or ; are skipped.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Text file with one name=value per line. |
| Encoding | `encoding` | Text | No | `utf-8` | Main | Text encoding: utf-8, ansi, unicode ... |
| Separator | `separator` | Text | No | `=` | Main | Text between the name and the value, e.g. = or : or a tab (\t). |
| Trim | `trim` | Yes/No | No | Yes | Main | Remove spaces around names and values. |
| Trim side | `trim_side` | Text | No | `both` | Main | Which spaces trim removes: on both sides, only before, or only after the name and the value. One of: `both`, `leading`, `trailing`; Shown when: `{"trim": ["true"]}` |
| Lowercase keys | `lowercase_keys` | Yes/No | No | No | Main | Turn names into lower case, so Server and server are the same variable. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | Name to value, in file order. |
| `count` | Number | Number of values read. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Wait for file

`ooa.file/wait_for_file` (action)

Wait until a file appears and has stopped growing (for example a download or an export), then return its path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to wait for; the name may use * and ? (e.g. Downloads/report*.xlsx), the newest match wins. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | How long to wait at most. Range: 0 to  |
| Stable (seconds) | `stable_seconds` | Number | No | `1` | More options | The file's size must stay the same for this long, so a file still being written is not taken. Range: 0 to  |
| Ignore temporary | `ignore_temporary` | Yes/No | No | Yes | More options | Skip browser download placeholders (.crdownload, .part, .tmp) and Office lock files (~$...). |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | More options | Fail with FILE.WAIT_TIMEOUT when time runs out; otherwise found is false. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Path of the file found; empty when time ran out and fail_on_timeout is off. |
| `found` | Yes/No | True when the file was found in time. |

*Time limit 3600 s; needs: filesystem.*

### Write text

`ooa.file/write_text` (action)

Write text to a file, overwriting or appending.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Fail if exists | `fail_if_exists` | Yes/No | No | No | Main | Stop instead of writing when the file already exists. |
| Text | `text` | Text | Yes |  | Main | Text to write. |
| Append | `append` | Yes/No | No | No | Main | Append instead of overwrite. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Text encoding, e.g. utf-8, utf-16le, windows-1252; ansi uses the runner's default. Example: `utf-16le` |

*Time limit 60 s; needs: filesystem.*

## Paths

### Get file name

`ooa.file/get_name` (action)

Return the last part of a path, with and without its extension; the file does not need to exist.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File or folder path. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Name with extension, e.g. report.pdf. |
| `name_without_extension` | Text | Name without the last extension, e.g. report. |
| `extension` | Text | Extension without the dot, e.g. pdf; empty when there is none. |

*Time limit 60 s; safe to repeat.*

### Get parent folder

`ooa.file/get_parent` (action)

Return the folder that contains a file or folder, as a full path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File or folder path. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folder` | File path | Parent folder, or empty for a drive or root. |

*Time limit 60 s; safe to repeat.*

## Folders

### List files

`ooa.file/list` (action)

List files in a folder matching a glob pattern.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | File path | Yes |  | Main | Folder path. |
| Pattern | `pattern` | Text | No | `*` | Main | Glob pattern, e.g. *.pdf. |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main | Also list matching files in sub-folders. |
| Minimum size (bytes) | `min_size_bytes` | Number | No |  | Main; tick Filter by size | Only files at least this large. Range: 0 to  |
| Maximum size (bytes) | `max_size_bytes` | Number | No |  | Main; tick Filter by size | Only files at most this large. Range: 0 to  |
| Modified within (days) | `modified_within_days` | Number | No |  | Main; tick Filter by date | Only files changed in the last this many days. Range: 0 to  |
| Older than (days) | `older_than_days` | Number | No |  | Main; tick Filter by date | Only files last changed more than this many days ago. Range: 0 to  |
| Sort by | `sort_by` | Text | No | `name` | More options | Order of the list: by path name, by last change (oldest first) or by size (smallest first). One of: `name`, `modified`, `size` |
| Descending | `descending` | Yes/No | No | No | More options | Reverse the order, e.g. newest first with sort_by modified. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | File paths in the chosen order. |
| `count` | Number | Number of files found. |
| `first` | File path | First file of the list (e.g. the newest with sort_by modified, descending); empty when none. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

## Archives

### Unzip

`ooa.file/unzip` (action)

Extract a .zip archive into a folder, also a password-protected one (AES or the older ZipCrypto); entries that would escape the folder are rejected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Archive | `archive` | File path | Yes |  | Main | Archive path. |
| Folder | `folder` | File path | Yes |  | Main | Destination folder. |
| Password | `password` | Credential (vault) | No |  | Main; tick The archive has a password | Password of a protected archive, from the vault. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace files that already exist; when false they are kept and skipped. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Extracted file paths. |

*Time limit 60 s; needs: filesystem.*

### Zip

`ooa.file/zip` (action)

Compress files or a folder into a .zip archive, optionally protected with a password (AES-256, opened by 7-Zip, WinZip and most tools).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | File path | Yes |  | Main | File or folder to compress. |
| Pattern | `pattern` | Text | No |  | Main | Only add files whose names match this glob pattern; all files when empty. Example: `*.pdf` |
| Target | `target` | File path | Yes |  | Main | Archive path, e.g. out/report.zip. |
| Password | `password` | Credential (vault) | No |  | Main; tick Protect with a password | Encrypt every file with this password (AES-256). Windows Explorer cannot open such archives; 7-Zip, WinZip and most tools can. |
| Delete originals | `delete_originals` | Yes/No | No | No | Main | Delete the files that were added once the archive is complete. |
| Compression | `compression` | Text | No | `normal` | Main | normal: default size and speed; fast and fastest trade size for speed; store adds files uncompressed. One of: `normal`, `fast`, `fastest`, `store` |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing archive; when false an existing archive stops the step. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Created archive. |

*Time limit 60 s; needs: filesystem.*

## Conditions

Used in If, Loop while and Wait until steps.

### File date is

`ooa.file/date_is` (condition)

Compare when a file was last changed or created with a date, or check its age in days.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Which | `which` | Text | No | `modified` | Main | Which date of the file. One of: `modified`, `created` |
| Operator | `operator` | Text | No | `before` | Main | Before, after or on a date (calendar day, UTC), or older/newer than a number of days. One of: `before`, `after`, `on`, `older_than_days`, `newer_than_days` |
| Date | `date` | DateTime | No |  | Main | Date to compare with (UTC). Shown when: `{"operator": ["before", "after", "on"]}` |
| Days | `days` | Number | No |  | Main | Age in days. Range: 0 to ; Shown when: `{"operator": ["older_than_days", "newer_than_days"]}` |

### File exists

`ooa.file/exists` (condition)

True when the file exists.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |

### File has extension

`ooa.file/has_extension` (condition)

True when the file name ends with one of the extensions, e.g. pdf, xlsx (case is ignored).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path or name. |
| Extensions | `extensions` | Text | Yes |  | Main | One or more extensions separated by commas, with or without the dot. Example: `pdf, xlsx` |

### File size is

`ooa.file/size_is` (condition)

Compare the size of a file with a number of bytes, KB or MB, e.g. larger than 0 bytes.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Operator | `operator` | Text | No | `gt` | Main | How to compare. One of: `eq`, `ne`, `gt`, `ge`, `lt`, `le` |
| Size | `size` | Number | Yes |  | Main | Size to compare with. Range: 0 to  |
| Unit | `unit` | Text | No | `bytes` | Main | Unit of the size (1 KB = 1024 bytes). One of: `bytes`, `KB`, `MB`, `GB` |

## Loop types

Used as the kind of loop in a Loop step.

### Each file in folder

`ooa.file/files_in_folder` (loop type)

Yield each file path in a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | File path | Yes |  | Main | Folder path. |
| Pattern | `pattern` | Text | No | `*` | Main | Glob pattern. |
