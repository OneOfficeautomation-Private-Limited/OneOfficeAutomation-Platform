# FTP / SFTP

Toolkit `ooa.file_transfer`, version 1.0.0. Connect to FTP, FTPS or SFTP servers to list, upload, download, rename and delete files.

[All toolkits](README.md)

## Contents

- **Folders**: Change folder, Delete folder, Download folder, Upload folder
- **Connection**: Connect, Disconnect
- **Files**: Create folder, Delete, Delete files, Download, List files, Rename or move, Upload

## Folders

### Change folder

`ooa.file_transfer/change_folder` (action)

Change the session's current remote folder; later relative paths start from it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Folder | `folder` | Text | Yes |  | Main | Remote folder, absolute or relative to the current one. Example: `/outgoing/invoices` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folder` | Text | Current remote folder after the change. |

*Time limit 120 s; needs: network.*

### Delete folder

`ooa.file_transfer/delete_folder` (action)

Delete a remote folder; a folder with contents is deleted only when recursive is true.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Folder | `folder` | Text | Yes |  | Main | Remote folder to delete. |
| Recursive | `recursive` | Yes/No | No | No | More options | Also delete everything inside. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted_files` | Number | Files deleted inside the folder. |

*Time limit 3600 s; needs: network.*

### Download folder

`ooa.file_transfer/download_folder` (action)

Download a remote folder with all its files and sub-folders into a local folder; optionally only files changed in the last days or between two dates.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Remote folder | `remote_folder` | Text | Yes |  | Main | Remote folder to download. |
| Pattern | `pattern` | Text | No | `*` | Main | Only files whose names match this wildcard (* and ?). Example: `*.csv` |
| Local folder | `local_folder` | File path | Yes |  | Main | Local folder that receives the contents. |
| Modified within (days) | `modified_within_days` | Number | No |  | Main; choice Changed = In the last days | Only files the server reports as changed in this many days up to now (0 = today, from midnight UTC). Range: 0 to  |
| Modified after | `modified_after` | DateTime | No |  | Main; choice Changed = Between dates | Only files changed at or after this time (UTC). |
| Modified before | `modified_before` | DateTime | No |  | Main; choice Changed = Between dates | Only files changed before this time (UTC). |
| Include subfolders | `include_subfolders` | Yes/No | No | Yes | More options | Also copy sub-folders and their files. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace files that already exist; when false they are skipped. |
| Only newer | `only_newer` | Yes/No | No | No | More options | Copy a file only when it is newer than the existing copy (by modified time). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Local paths of the downloaded files. |

*Time limit 7200 s; needs: network, filesystem.*

### Upload folder

`ooa.file_transfer/upload_folder` (action)

Upload a local folder with all its files and sub-folders, creating remote folders as needed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Local folder | `local_folder` | File path | Yes |  | Main | Local folder to upload. |
| Pattern | `pattern` | Text | No | `*` | Main | Only files whose names match this wildcard (* and ?). Example: `*.csv` |
| Remote folder | `remote_folder` | Text | Yes |  | Main | Remote folder that receives the contents. |
| Include subfolders | `include_subfolders` | Yes/No | No | Yes | More options | Also copy sub-folders and their files. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace files that already exist; when false they are skipped. |
| Only newer | `only_newer` | Yes/No | No | No | More options | Copy a file only when it is newer than the existing copy (by modified time). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Remote paths of the uploaded files. |

*Time limit 7200 s; needs: network, filesystem.*

## Connection

### Connect

`ooa.file_transfer/connect` (action)

Connect and sign in to an FTP, FTPS or SFTP server, optionally trying again when the server cannot be reached and starting in a chosen folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Server name or address. |
| Port | `port` | Number | No |  | Main | Port; 22 for SFTP, 21 for FTP/FTPS when empty. Range: 1 to 65535 |
| Protocol | `protocol` | Text | Yes |  | Main | sftp (SSH), ftps (FTP over TLS) or plain ftp. One of: `sftp`, `ftps`, `ftp` |
| User | `user` | Text | Yes |  | Main | User name. |
| Password | `password` | Credential (vault) | No |  | Main | Password from the vault. |
| Private key | `private_key` | File path | No |  | Main | SFTP private key file (instead of a password). Shown when: `{"protocol": ["sftp"]}` |
| Key passphrase | `key_passphrase` | Credential (vault) | No |  | Main | Private key passphrase. Shown when: `{"protocol": ["sftp"]}` |
| Host key fingerprint | `host_key_fingerprint` | Text | No |  | Main | Expected SFTP server fingerprint (SHA256:...); strongly recommended. Shown when: `{"protocol": ["sftp"]}` |
| Passive mode | `passive_mode` | Yes/No | No | Yes | Main | FTP/FTPS: the client opens the data connections (works through most firewalls); false uses active mode. Shown when: `{"protocol": ["ftp", "ftps"]}` |
| Implicit TLS | `implicit_tls` | Yes/No | No | No | Main | FTPS: use implicit TLS (usually port 990) instead of explicit TLS after connecting. Shown when: `{"protocol": ["ftps"]}` |
| Transfer mode | `transfer_mode` | Text | No | `binary` | Main | FTP/FTPS: binary copies files byte for byte; ascii converts line endings of text files. One of: `binary`, `ascii`; Shown when: `{"protocol": ["ftp", "ftps"]}` |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `20` | More options | How long to wait when connecting and for each server reply. |
| Retry attempts | `retry_attempts` | Number | No | `0` | More options; tick Try again when connecting fails | How many more times to try when the server cannot be reached or drops the connection; 0 tries once. A wrong password or server fingerprint is never retried. Range: 0 to 20 |
| Retry delay (seconds) | `retry_delay_seconds` | Number | No | `5` | More options; tick Try again when connecting fails | Seconds to wait between tries. Range: 0 to 600 |
| Start folder | `start_folder` | Text | No |  | More options | Remote folder to change to right after signing in; later relative paths start from it. Example: `/outgoing` |

*Time limit 600 s; needs: network.*

### Disconnect

`ooa.file_transfer/disconnect` (action)

Close the connection.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |

*Time limit 60 s.*

## Files

### Create folder

`ooa.file_transfer/create_folder` (action)

Create a remote folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Folder | `folder` | Text | Yes |  | Main | Remote folder. |

*Time limit 120 s; needs: network.*

### Delete

`ooa.file_transfer/delete` (action)

Delete a remote file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Remote path | `remote_path` | Text | Yes |  | Main | Remote file. |

*Time limit 120 s; needs: network.*

### Delete files

`ooa.file_transfer/delete_files` (action)

Delete the files in a remote folder whose names match a wildcard such as *.tmp.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Folder | `folder` | Text | Yes |  | Main | Remote folder. |
| Pattern | `pattern` | Text | Yes |  | Main | Wildcard of the files to delete (* and ?); * deletes every file. Example: `*.tmp` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | List | Remote paths of the deleted files. |

*Time limit 3600 s; needs: network.*

### Download

`ooa.file_transfer/download` (action)

Download a remote file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Remote path | `remote_path` | Text | Yes |  | Main | Remote file. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save it. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing local file; when false an existing file stops the step. |

*Time limit 3600 s; needs: network, filesystem.*

### List files

`ooa.file_transfer/list` (action)

List a remote folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Folder | `folder` | Text | No | `.` | Main | Remote folder. |
| Pattern | `pattern` | Text | No | `*` | Main | Only names matching this wildcard (* and ?). Example: `*.csv` |
| Kind | `kind` | Text | No | `all` | Main | List files, folders or both. One of: `all`, `files`, `folders` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: name, is_folder, size, modified. |

*Time limit 300 s; safe to repeat; needs: network.*

### Rename or move

`ooa.file_transfer/rename` (action)

Rename or move a remote file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| From | `from` | Text | Yes |  | Main | Current path. |
| To | `to` | Text | Yes |  | Main | New path. |

*Time limit 120 s; needs: network.*

### Upload

`ooa.file_transfer/upload` (action)

Upload a local file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `transfer` | Main | Session name. |
| Local path | `local_path` | File path | Yes |  | Main | File to upload. |
| Remote path | `remote_path` | Text | Yes |  | Main | Remote path including the file name. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing remote file; when false an existing file stops the step. |

*Time limit 3600 s; needs: network, filesystem.*
