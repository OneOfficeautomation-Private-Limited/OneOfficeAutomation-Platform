# Box

Toolkit `ooa.box`, version 1.0.0. Work with Box folders and files: upload, download, copy, move, search, share links and invite collaborators.

[All toolkits](README.md)

## Contents

- **Sharing**: Invite collaborator, Create shared link, List collaborators, Remove collaborator, Remove shared link
- **Files**: Copy file, Delete file, Download file, Get file info, Move file, Rename file, Upload file
- **Folders**: Create folder, Delete folder, List folder items
- **Search**: Search

## Sharing

### Invite collaborator

`ooa.box/add_collaborator` (action)

Give a person or group access to a file or folder with a chosen role.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Item type | `item_type` | Text | Yes |  | Main | Whether the item is a file or a folder. One of: `file`, `folder` |
| Item ID | `item_id` | Text | Yes |  | Main | Id of the file or folder. |
| Email | `email` | Text | No |  | Main; choice Invite = A person | Email address of the person to invite. |
| Group ID | `group_id` | Text | No |  | Main; choice Invite = A group | Box group id to invite instead of a person. |
| Role | `role` | Text | No | `viewer` | Main | What they may do with the item. One of: `editor`, `viewer`, `previewer`, `uploader`, `previewer uploader`, `viewer uploader`, `co-owner` |
| Notify | `notify` | Yes/No | No | Yes | Main | Send them an email about the invitation. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `collaboration_id` | Text | Id of the new collaboration, used to remove it later. |

*Time limit 60 s; needs: network.*

### Create shared link

`ooa.box/create_shared_link` (action)

Create or change the shared link of a file or folder and return the link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Item type | `item_type` | Text | Yes |  | Main | Whether the item is a file or a folder. One of: `file`, `folder` |
| Item ID | `item_id` | Text | Yes |  | Main | Id of the file or folder. |
| Access | `access` | Text | No | `company` | Main | Who can use the link: open (anyone with the link), company (people in your organization) or collaborators (invited people only). One of: `open`, `company`, `collaborators` |
| Allow download | `allow_download` | Yes/No | No | Yes | Main | Let people with the link download the item. |
| Expires at | `expires_at` | DateTime | No |  | Main; tick Set an end date | When the link stops working (UTC). Leave empty for no end date. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `url` | Text | Shared link to the item. |
| `download_url` | Text | Direct download link (files only, when downloads are allowed). |

*Time limit 60 s; needs: network.*

### List collaborators

`ooa.box/list_collaborators` (action)

Return who has been invited to a file or folder and their role.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Item type | `item_type` | Text | Yes |  | Main | Whether the item is a file or a folder. One of: `file`, `folder` |
| Item ID | `item_id` | Text | Yes |  | Main | Id of the file or folder. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: collaboration_id, name, login, type, role, status. |

*Time limit 60 s; safe to repeat; needs: network.*

### Remove collaborator

`ooa.box/remove_collaborator` (action)

Take away someone's access that was given by an invitation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Collaboration ID | `collaboration_id` | Text | Yes |  | Main | Id from Invite collaborator or List collaborators. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

### Remove shared link

`ooa.box/remove_shared_link` (action)

Turn off the shared link of a file or folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Item type | `item_type` | Text | Yes |  | Main | Whether the item is a file or a folder. One of: `file`, `folder` |
| Item ID | `item_id` | Text | Yes |  | Main | Id of the file or folder. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

## Files

### Copy file

`ooa.box/copy_file` (action)

Copy a file into a folder, optionally under a new name. Can replace a file of the same name there by adding the copy as its new version.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File to copy. |
| Target folder ID | `target_folder_id` | Text | Yes |  | Main | Folder that receives the copy. |
| New name | `new_name` | Text | No |  | Main | Name for the copy. Leave empty to keep the same name. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | If a file of the same name is already in the target folder, upload the copy as a new version of that file instead of failing. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Upload address (URL) | `upload_url` | Text | No | `https://upload.box.com` | More options | Box upload address. Leave the default unless your administrator gives you another. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file_id` | Text | Id of the copy. |

*Time limit 60 s; needs: network.*

### Delete file

`ooa.box/delete_file` (action)

Move a file to the Box trash, or delete it for good.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File to delete. |
| Permanently | `permanently` | Yes/No | No | No | More options | Also remove the file from the trash, so it cannot be restored. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

### Download file

`ooa.box/download_file` (action)

Save a Box file to this machine.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | Box file to download. |
| Save as | `save_as` | File path | Yes |  | Main | Where to save it, e.g. downloads/invoice.pdf. Missing folders are created. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a local file that is already at save_as; when off, the step fails instead. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the saved file. |

*Time limit 1800 s; needs: network, filesystem.*

### Get file info

`ooa.box/get_file_info` (action)

Read a file's name, size, dates, owner and folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File to look up. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | id, name, size, created_at, modified_at, sha1, parent_id, parent_name, owner, shared_link. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move file

`ooa.box/move_file` (action)

Move a file to another folder. Can replace a file of the same name there, which then goes to the Box trash.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File to move. |
| Target folder ID | `target_folder_id` | Text | Yes |  | Main | Folder to move it into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | If a file of the same name is already in the target folder, move that file to the trash and then move this one. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

### Rename file

`ooa.box/rename_file` (action)

Give a file a new name in the same folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File to rename. |
| New name | `new_name` | Text | Yes |  | Main | New file name including the extension, e.g. Q4-report.xlsx. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

### Upload file

`ooa.box/upload_file` (action)

Upload a local file to a Box folder. Can replace the existing file of the same name as a new version.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Folder ID | `folder_id` | Text | No | `0` | Main | Box folder to upload into. 0 is the top level of All Files. |
| Local path | `local_path` | File path | Yes |  | Main | File on this machine to upload. |
| File name | `file_name` | Text | No |  | Main | Name to give the file in Box. Leave empty to keep the local name. |
| Replace existing | `replace_existing` | Yes/No | No | No | Main | If a file of this name is already there, upload as its new version instead of failing. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Upload address (URL) | `upload_url` | Text | No | `https://upload.box.com` | More options | Box upload address. Leave the default unless your administrator gives you another. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file_id` | Text | Id of the uploaded file. |

*Time limit 1800 s; needs: network, filesystem.*

## Folders

### Create folder

`ooa.box/create_folder` (action)

Create a folder inside another folder and return its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Parent folder ID | `parent_folder_id` | Text | No | `0` | Main | Folder to create it in. 0 is the top level of All Files. |
| Name | `name` | Text | Yes |  | Main | Name of the new folder. |
| Reuse existing | `reuse_existing` | Yes/No | No | Yes | More options | If a folder with this name is already there, return its id instead of failing. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folder_id` | Text | Id of the new (or existing) folder. |

*Time limit 60 s; needs: network.*

### Delete folder

`ooa.box/delete_folder` (action)

Move a folder to the Box trash. A folder that still has content is only removed when you allow it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Folder ID | `folder_id` | Text | Yes |  | Main | Folder to delete. |
| Include contents | `include_contents` | Yes/No | No | No | Main | Also delete everything inside the folder. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

*Time limit 60 s; needs: network.*

### List folder items

`ooa.box/list_folder` (action)

Return the files, folders and web links inside a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Folder ID | `folder_id` | Text | No | `0` | Main | Folder to look in. 0 is the top level of All Files. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most items to return. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, type, name, size, modified_at, parent_id. |
| `count` | Number | Number of items returned. |

*Time limit 600 s; safe to repeat; needs: network.*

## Search

### Search

`ooa.box/search` (action)

Find files and folders by name, content or description.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Box access token from the vault. |
| Query | `query` | Text | Yes |  | Main | Words to look for. Example: `invoice 2026` |
| File extensions | `file_extensions` | Text | No |  | Main | Only these file types, separated by commas. Example: `pdf,docx` |
| Only in folder (ID) | `in_folder_id` | Text | No |  | Main | Only search inside this folder and its sub-folders. |
| Item type | `item_type` | Text | No | `any` | Main | Only files, only folders, or both. One of: `any`, `file`, `folder` |
| Maximum results | `max_results` | Number | No | `200` | More options | Most results to return. |
| As user | `as_user` | Text | No |  | More options | Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself. |
| Endpoint address | `base_url` | Text | No | `https://api.box.com` | More options | Box API address. Leave the default unless your administrator gives you another. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, type, name, size, modified_at, parent_id. |
| `count` | Number | Number of results. |

*Time limit 600 s; safe to repeat; needs: network.*
