# Folder

Toolkit `ooa.folder`, version 1.0.0. Create, list, copy and delete folders.

[All toolkits](README.md)

## Contents

- **Folders**: Copy folder, Create folder, Create folder shortcut, Create folder symbolic link, Delete folder, List subfolders, Open folder, Rename folder
- **Conditions**: Folder exists, Folder is empty
- **Loop types**: Each folder in folder

## Folders

### Copy folder

`ooa.folder/copy` (action)

Copy a folder and its contents, optionally only some files by name, size or date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | File path | Yes |  | Main | Source folder. |
| Target | `target` | File path | Yes |  | Main | Target folder. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace files that already exist in the target; when false they are kept. |
| Larger than (KB) | `larger_than_kb` | Number | No |  | Main; tick Filter by size | Only copy files larger than this many KB (1 KB = 1024 bytes). Range: 0 to  |
| Smaller than (KB) | `smaller_than_kb` | Number | No |  | Main; tick Filter by size | Only copy files smaller than this many KB. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; tick Filter by date | Only copy files last changed after this date and time (local time). |
| Modified before | `modified_before` | DateTime | No |  | Main; tick Filter by date | Only copy files last changed before this date and time (local time). |
| Pattern | `pattern` | Text | No |  | More options | Only copy files whose names match this glob pattern; all files when empty. Example: `*.pdf` |
| Only newer | `only_newer` | Yes/No | No | No | More options | Copy a file only when it is newer than the one already in the target. Shown when: `{"overwrite": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `copied` | Number | Number of files copied. |

*Time limit 60 s; needs: filesystem.*

### Create folder

`ooa.folder/create` (action)

Create a folder and any missing parents.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |
| Fail if exists | `fail_if_exists` | Yes/No | No | No | Main | Stop when the folder already exists instead of continuing. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Create folder shortcut

`ooa.folder/create_shortcut` (action)

Create a shortcut that opens a folder: a .lnk file on Windows, a .desktop file or symbolic link elsewhere.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder the shortcut opens. |
| Shortcut | `shortcut` | File path | Yes |  | Main | Shortcut to create; .lnk is added on Windows when missing. |
| Comment | `comment` | Text | No |  | More options | Tooltip text shown for the shortcut. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace an existing shortcut. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Created shortcut. |

*Time limit 60 s; needs: filesystem.*

### Create folder symbolic link

`ooa.folder/create_symbolic_link` (action)

Create a symbolic link that points to a folder; on Windows this needs Developer Mode or administrator rights.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder the link points to. |
| Link | `link` | File path | Yes |  | Main | Link to create. |
| Overwrite | `overwrite` | Yes/No | No | No | More options | Replace an existing link (never a real folder). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Created link. |

*Time limit 60 s; needs: filesystem.*

### Delete folder

`ooa.folder/delete` (action)

Delete a folder, optionally only when its total size or its last-change date meets limits. Non-empty folders are deleted only when recursive is true.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |
| Recursive | `recursive` | Yes/No | No | No | Main | Also delete everything inside. |
| Larger than (KB) | `larger_than_kb` | Number | No |  | Main; tick Only when the size fits | Only when the folder holds more than this many KB in all its files, at any depth (1 KB = 1024 bytes). Range: 0 to  |
| Smaller than (KB) | `smaller_than_kb` | Number | No |  | Main; tick Only when the size fits | Only when the folder holds less than this many KB in all its files, at any depth. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; tick Only when the date fits | Only when the folder itself was last changed after this date and time (local time); adding or removing an item directly inside changes it. |
| Modified before | `modified_before` | DateTime | No |  | Main; tick Only when the date fits | Only when the folder itself was last changed before this date and time (local time). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | Yes/No | True when the folder was deleted; false when it did not exist or did not meet the limits. |

*Time limit 60 s; needs: filesystem.*

### List subfolders

`ooa.folder/list_subfolders` (action)

List the folders directly inside a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folders` | List | Sorted folder paths. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Open folder

`ooa.folder/open_folder` (action)

Show a folder in the file manager (File Explorer on Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder to show. |

*Time limit 60 s; needs: desktop, filesystem.*

### Rename folder

`ooa.folder/rename_folder` (action)

Give a folder a new name in the same parent folder, optionally only when its total size or its last-change date meets limits.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder to rename. |
| New name | `new_name` | Text | Yes |  | Main | New name only, without any path. Example: `Archive 2026` |
| Larger than (KB) | `larger_than_kb` | Number | No |  | Main; tick Only when the size fits | Only when the folder holds more than this many KB in all its files, at any depth (1 KB = 1024 bytes). Range: 0 to  |
| Smaller than (KB) | `smaller_than_kb` | Number | No |  | Main; tick Only when the size fits | Only when the folder holds less than this many KB in all its files, at any depth. Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; tick Only when the date fits | Only when the folder itself was last changed after this date and time (local time); adding or removing an item directly inside changes it. |
| Modified before | `modified_before` | DateTime | No |  | Main; tick Only when the date fits | Only when the folder itself was last changed before this date and time (local time). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | New full path of the folder; the unchanged path when it did not meet the limits. |
| `renamed` | Yes/No | True when the folder was renamed; false when it did not meet the limits. |

*Time limit 60 s; needs: filesystem.*

## Conditions

Used in If, Loop while and Wait until steps.

### Folder exists

`ooa.folder/exists` (condition)

True when the folder exists.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |

### Folder is empty

`ooa.folder/is_empty` (condition)

True when the folder exists and contains nothing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |

## Loop types

Used as the kind of loop in a Loop step.

### Each folder in folder

`ooa.folder/subfolders` (loop type)

Yield the path of each folder inside a folder, optionally with all folders below it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Folder path. |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main | Also yield folders inside the sub-folders, at any depth. |
| Pattern | `pattern` | Text | No | `*` | Main | Glob pattern for the folder names, e.g. 2026-*. |
