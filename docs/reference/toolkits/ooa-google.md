# Google Workspace

Toolkit `ooa.google`, version 1.0.0. Read and write Google Sheets, upload and download Drive files, manage Calendar events and send Gmail.

[All toolkits](README.md)

## Contents

- **Calendar**: Create Google Calendar event, Delete Google Calendar event, List Google Calendar events, Update Google Calendar event
- **Drive**: Check Drive permission, Copy Drive file, Copy Drive folder, Create Drive folder, Share Drive item, Delete Drive item, Remove Drive permission, Download from Drive, Export Google file, Find Drive items, Get Drive item info, List Drive files, List Drive permissions, Move Drive item, Rename Drive item, Change Drive permission, Upload to Drive
- **Gmail**: Delete all matching Gmail messages, Delete Gmail message, Forward Gmail message, Read Gmail message, Check Gmail label exists, List Gmail messages, Change Gmail message status, Move all matching Gmail messages, Move Gmail message, Reply all to Gmail message, Reply to Gmail message, Save attachments of all matching Gmail messages, Save Gmail attachments, Save Gmail message as .eml, Send Gmail
- **Sheets**: Add sheet, Append rows to sheet, Autofit columns, Autofit rows, Clear range, Copy range, Create spreadsheet, Delete columns, Delete range, Delete rows, Delete sheet, Find cells, Format cells, Get cell value, Get cell colour, Get range values, Hide sheet, Insert columns, Insert range, Insert rows, List sheets, Read sheet range, Read column, Read formula, Read row, Rename sheet, Get used row count, Set cell value, Set formula, Show sheet, Write to sheet, Write data table

## Calendar

### Create Google Calendar event

`ooa.google/calendar_create_event` (action)

Create an event, optionally inviting attendees.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Subject | `subject` | Text | Yes |  | Main | Title. |
| Location | `location` | Text | No |  | Main | Location. |
| Attendees | `attendees` | Any | No |  | Main | Attendee address(es). |
| Start | `start` | DateTime | Yes |  | Main | Start. |
| End | `end` | DateTime | Yes |  | Main | End. |
| All day | `all_day` | Yes/No | No | No | Main | All-day event: only the dates count; the end date may equal the start date. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | IANA time zone of start and end. |
| Recurrence | `recurrence` | Text | No |  | Main; tick Repeat the event | Repeat rule (RFC 5545), or simply daily, weekly, monthly or yearly. Example: `RRULE:FREQ=WEEKLY;COUNT=10` |
| Visibility | `visibility` | Text | No | `default` | Main | Who can see the event's details. One of: `default`, `public`, `private` |
| Description | `description` | Text | No |  | Main | Description. |
| Optional attendees | `optional_attendees` | Any | No |  | More options | Address(es) invited as optional. |
| Calendar ID | `calendar_id` | Text | No | `primary` | More options | Calendar id. |
| Notify | `notify` | Yes/No | No | Yes | More options | Email the invitation to the attendees. |
| Add meet link | `add_meet_link` | Yes/No | No | No | More options | Add a Google Meet video link. |
| Reminder (minutes) | `reminder_minutes` | Number | No |  | More options | Popup reminder this many minutes before; the calendar's default reminders when empty. |
| Show as | `show_as` | Text | No | `busy` | More options | Whether the time shows as busy. One of: `busy`, `free` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `event_id` | Text | Event id. |
| `link` | Text | Link to the event. |
| `meet_link` | Text | Google Meet link, when add_meet_link is true. |

*Time limit 60 s; needs: network.*

### Delete Google Calendar event

`ooa.google/calendar_delete_event` (action)

Delete an event from a calendar, optionally telling the attendees.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id. |
| Notify | `notify` | Yes/No | No | Yes | More options | Email the attendees that the event is cancelled. |
| Calendar ID | `calendar_id` | Text | No | `primary` | More options | Calendar id. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### List Google Calendar events

`ooa.google/calendar_list_events` (action)

List the events of a calendar between two times, earliest first, with recurring events expanded.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Start | `start` | DateTime | Yes |  | Main | Events ending after this time. |
| End | `end` | DateTime | Yes |  | Main | Events starting before this time. |
| Search | `search` | Text | No |  | Main | Only events containing this text. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | IANA time zone of start, end and the returned times. |
| Maximum results | `max_results` | Number | No | `250` | More options | Maximum events returned. |
| Calendar ID | `calendar_id` | Text | No | `primary` | More options | Calendar id. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, subject, start, end, all_day, location, organizer, attendees, status, link. |
| `count` | Number | Number of events. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update Google Calendar event

`ooa.google/calendar_update_event` (action)

Change the title, time, description, location or attendees of an event; empty inputs stay as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id. |
| Subject | `subject` | Text | No |  | Main | New title. |
| Location | `location` | Text | No |  | Main | New location. |
| Attendees | `attendees` | Any | No |  | Main | Replaces the attendee list: address(es) as text or a List. |
| Start | `start` | DateTime | No |  | Main | New start. |
| End | `end` | DateTime | No |  | Main | New end. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | IANA time zone of start and end. |
| Description | `description` | Text | No |  | Main | New description. |
| Notify | `notify` | Yes/No | No | Yes | More options | Email the attendees about the change. |
| Calendar ID | `calendar_id` | Text | No | `primary` | More options | Calendar id. |
| Visibility | `visibility` | Text | No |  | More options | Who can see the event's details; unchanged when empty. One of: `default`, `public`, `private` |
| Reminder (minutes) | `reminder_minutes` | Number | No |  | More options | Popup reminder this many minutes before; unchanged when empty. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `link` | Text | Link to the event. |

*Time limit 60 s; safe to repeat; needs: network.*

## Drive

### Check Drive permission

`ooa.google/drive_check_permission` (action)

Check whether a person (or their domain, or anyone with the link) can read, comment on or edit a file or folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Access | `access` | Text | No | `read` | Main | The access to check. One of: `read`, `comment`, `write` |
| Email | `email` | Text | Yes |  | Main | The person's email address. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `has_access` | Yes/No | True when the person has at least the asked access. |
| `role` | Text | The highest role found, empty when none. |

*Time limit 60 s; safe to repeat; needs: network.*

### Copy Drive file

`ooa.google/drive_copy_file` (action)

Make a copy of a Drive file, optionally renamed or in another folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| File ID | `file_id` | Text | No |  | Main; choice Pick the file by = Id | File to copy; or give item_path instead. |
| Item path | `item_path` | Text | No |  | Main; choice Pick the file by = Path | Path of the file under My Drive, used instead of file_id. Example: `Reports/2026/summary.pdf` |
| Folder ID | `folder_id` | Text | No |  | Main; choice Put the copy in = A folder id | Folder for the copy; next to the original when empty. |
| Destination path | `destination_path` | Text | No |  | Main; choice Put the copy in = A folder path | Path of the folder for the copy under My Drive, used instead of folder_id. Example: `Reports/2026` |
| Name | `name` | Text | No |  | Main; tick Give the copy a new name | Name of the copy; Drive's default when empty. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file_id` | Text | Id of the copy. |

*Time limit 60 s; needs: network.*

### Copy Drive folder

`ooa.google/drive_copy_folder` (action)

Copy a folder with all its files and subfolders into another folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Folder ID | `folder_id` | Text | No |  | Main; choice Pick the folder by = Id | Folder to copy; or give folder_path instead. |
| Folder path | `folder_path` | Text | No |  | Main; choice Pick the folder by = Path | Path of the folder to copy under My Drive, used instead of folder_id. Example: `Reports/2026` |
| Destination ID | `destination_id` | Text | No |  | Main; choice Put the copy in = A folder id | Folder that receives the copy; next to the original when empty. |
| Destination path | `destination_path` | Text | No |  | Main; choice Put the copy in = A folder path | Path of the folder that receives the copy under My Drive, used instead of destination_id. Example: `Archive` |
| Name | `name` | Text | No |  | More options | Name of the copy; the original name when empty. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folder_id` | Text | Id of the new folder. |
| `file_count` | Number | Number of files copied. |

*Time limit 1800 s; needs: network.*

### Create Drive folder

`ooa.google/drive_create_folder` (action)

Create a folder in Drive.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Parent ID | `parent_id` | Text | No |  | Main | Parent folder id; My Drive root when empty. |
| Name | `name` | Text | Yes |  | Main | Folder name. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folder_id` | Text | Id of the new folder. |
| `web_link` | Text | Link to open the folder. |

*Time limit 60 s; needs: network.*

### Share Drive item

`ooa.google/drive_create_permission` (action)

Give a user, group, domain or anyone with the link access to a file or folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Role | `role` | Text | No | `reader` | Main | Access level. One of: `reader`, `commenter`, `writer`, `fileOrganizer`, `organizer` |
| Share with | `share_with` | Text | No | `user` | Main | Who receives access. One of: `user`, `group`, `domain`, `anyone` |
| Email | `email` | Text | No |  | Main | User or group address (for user and group). |
| Domain | `domain` | Text | No |  | Main | Domain such as example.com (for domain). |
| Notify | `notify` | Yes/No | No | Yes | More options | Send a notification email (user and group only). |
| Message | `message` | Text | No |  | More options | Text added to the notification email. |
| Expires | `expires` | DateTime | No |  | More options | When the access ends (UTC; users and groups only, Google Workspace accounts). |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `permission_id` | Text | Id of the new permission. |

*Time limit 60 s; needs: network.*

### Delete Drive item

`ooa.google/drive_delete_item` (action)

Move a file or folder to the Drive trash, or delete it permanently.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | No |  | Main; choice Pick the item by = Id | File or folder id; or give item_path instead. |
| Item path | `item_path` | Text | No |  | Main; choice Pick the item by = Path | Path of the file or folder under My Drive, used instead of item_id. Example: `Old/report.pdf` |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good instead of moving to the trash. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Remove Drive permission

`ooa.google/drive_delete_permission` (action)

Take away a permission from a file or folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Permission ID | `permission_id` | Text | Yes |  | Main | Permission id from List Drive permissions. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Download from Drive

`ooa.google/drive_download` (action)

Download a Drive file by id (Google Docs/Sheets are exported to PDF/XLSX automatically).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | File id. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save it. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing local file; when false a number such as (2) is added to the name. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 600 s; needs: network, filesystem.*

### Export Google file

`ooa.google/drive_download_export` (action)

Save a Google Doc, Sheet or Slides file in an office format such as PDF, XLSX or DOCX.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| File ID | `file_id` | Text | Yes |  | Main | Id of the Google Doc, Sheet or Slides file. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save it; the folder to save into when file_name is given. |
| Format | `format` | Text | No | `pdf` | Main | Output format. One of: `pdf`, `xlsx`, `docx`, `pptx`, `csv`, `txt`, `html`, `odt`, `ods` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing local file; when false a number such as (2) is added to the name. |
| File name | `file_name` | Text | No |  | More options | Name for the saved file inside the local_path folder; the format's extension is added when the name has none. Example: `Q4 report` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 600 s; needs: network, filesystem.*

### Find Drive items

`ooa.google/drive_find_items` (action)

Find files and folders by name, folder, type or a raw Drive query.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item type | `item_type` | Text | No | `any` | Main | Files, folders or both. One of: `any`, `file`, `folder` |
| Folder ID | `folder_id` | Text | No |  | Main; choice Look in = A folder id | Only items directly inside this folder. |
| Folder path | `folder_path` | Text | No |  | Main; choice Look in = A folder path | Only items directly inside the folder at this path under My Drive; used instead of folder_id. Example: `Reports/2026` |
| Name equals | `name_equals` | Text | No |  | Main | Name is exactly this. |
| Name contains | `name_contains` | Text | No |  | Main | Name contains this text. |
| MIME type | `mime_type` | Text | No |  | More options | Only this MIME type. Example: `application/pdf` |
| Extra query | `extra_query` | Text | No |  | More options | Further Drive query terms joined with and. Example: `modifiedTime > '2026-01-01T00:00:00'` |
| Include trashed | `include_trashed` | Yes/No | No | No | More options | Also return items in the trash. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Maximum items returned. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, mime_type, is_folder, size, modified, web_link. |
| `count` | Number | Number of items found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get Drive item info

`ooa.google/drive_get_file_info` (action)

Read the details of a file or folder: name, type, size, dates, owners, parents and link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | id, name, mime_type, is_folder, size, created, modified, parents, web_link, owners, trashed. |

*Time limit 60 s; safe to repeat; needs: network.*

### List Drive files

`ooa.google/drive_list` (action)

List files in a Drive folder (or matching a Drive search query).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Folder ID | `folder_id` | Text | No |  | Main | Folder id; all files when empty. |
| Name contains | `name_contains` | Text | No |  | Main | Only names containing this text. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, mime_type, size, modified. |

*Time limit 60 s; safe to repeat; needs: network.*

### List Drive permissions

`ooa.google/drive_list_permissions` (action)

List who has access to a file or folder and with which role.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: permission_id, type, role, email, domain, display_name. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move Drive item

`ooa.google/drive_move_item` (action)

Move a file or folder into another folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | No |  | Main; choice Pick the item by = Id | File or folder id; or give item_path instead. |
| Item path | `item_path` | Text | No |  | Main; choice Pick the item by = Path | Path of the file or folder under My Drive, used instead of item_id. Example: `Inbox/invoice.pdf` |
| Folder ID | `folder_id` | Text | No |  | Main; choice Move into = A folder id | Destination folder id; or give destination_path instead. |
| Destination path | `destination_path` | Text | No |  | Main; choice Move into = A folder path | Path of the destination folder under My Drive, used instead of folder_id. Example: `Archive/2026` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Rename Drive item

`ooa.google/drive_rename_item` (action)

Give a file or folder a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | No |  | Main; choice Pick the item by = Id | File or folder id; or give item_path instead. |
| Item path | `item_path` | Text | No |  | Main; choice Pick the item by = Path | Path of the file or folder under My Drive, used instead of item_id. Example: `Reports/draft.docx` |
| New name | `new_name` | Text | Yes |  | Main | New name. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Change Drive permission

`ooa.google/drive_update_permission` (action)

Change the role of an existing permission on a file or folder, e.g. from reader to writer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Item ID | `item_id` | Text | Yes |  | Main | File or folder id. |
| Permission ID | `permission_id` | Text | Yes |  | Main | Permission id from List Drive permissions or Share Drive item. |
| Role | `role` | Text | Yes |  | Main | New access level. One of: `reader`, `commenter`, `writer`, `fileOrganizer`, `organizer` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Upload to Drive

`ooa.google/drive_upload` (action)

Upload a local file to a Drive folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Local path | `local_path` | File path | Yes |  | Main | File to upload. |
| Folder ID | `folder_id` | Text | No |  | Main | Target folder id; My Drive root when empty. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace the content of a file with the same name in the folder (keeping its id and links) instead of adding a second file. |
| Name | `name` | Text | No |  | More options | Name in Drive; the local name when empty. |
| Description | `description` | Text | No |  | More options | Description stored with the file. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file_id` | Text | New file id. |

*Time limit 600 s; needs: network, filesystem.*

## Gmail

### Delete all matching Gmail messages

`ooa.google/gmail_delete_all` (action)

Trash, or permanently delete, every Gmail message that matches a search.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Status | `status` | Text | No | `any` | Main | Only unread or only read messages. One of: `any`, `unread`, `read` |
| Label | `label` | Text | No |  | Main | Only messages with this label, e.g. INBOX or Invoices. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains these words; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Query | `query` | Text | No |  | More options | Gmail search selecting the messages; a search or at least one filter is required so a mailbox is never emptied by accident. Example: `from:alerts@acme.com older_than:30d` |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good instead of moving to the trash. |
| Maximum messages | `max_messages` | Number | No | `500` | More options | Upper limit of messages handled in one run. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of messages deleted. |

*Time limit 600 s; needs: network.*

### Delete Gmail message

`ooa.google/gmail_delete_message` (action)

Move a Gmail message to the trash, or delete it permanently.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good instead of moving to the trash (needs full mail access). |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Forward Gmail message

`ooa.google/gmail_forward_message` (action)

Forward a Gmail message with a note, either inline with its attachments or as an attached .eml file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| To | `to` | Any | Yes |  | Main | Recipient address(es). |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Attachments | `attachments` | List | No |  | Main | More file paths to attach. |
| Note | `note` | Text | No | (empty) | Main; tick Add a note above the message | Text placed above the forwarded message. |
| Body is HTML | `html` | Yes/No | No | No | Main; tick Add a note above the message | Note is HTML. |
| As attachment | `as_attachment` | Yes/No | No | No | More options | Attach the original as an .eml file instead of quoting it. |
| Include attachments | `include_attachments` | Yes/No | No | Yes | More options | Inline mode: also forward the original attachments. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Gmail id of the sent message. |

*Time limit 60 s; needs: network.*

### Read Gmail message

`ooa.google/gmail_get_message` (action)

Read one Gmail message: headers, text and HTML body, labels and attachment names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Mark read | `mark_read` | Yes/No | No | No | Main | Mark the message as read. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `email` | Dictionary | id, thread_id, from, to, cc, subject, date, message_id, text, html, attachments, labels. |

*Time limit 60 s; safe to repeat; needs: network.*

### Check Gmail label exists

`ooa.google/gmail_label_exists` (action)

Check whether a Gmail label exists, by name (not case-sensitive) or id, and return its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Label | `label` | Text | Yes |  | Main | Label name or id. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exists` | Yes/No | True when the label exists. |
| `label_id` | Text | Label id, empty when it does not exist. |

*Time limit 60 s; safe to repeat; needs: network.*

### List Gmail messages

`ooa.google/gmail_list_messages` (action)

Find Gmail messages with a Gmail search such as from:billing@acme.com is:unread and return them as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Status | `status` | Text | No | `any` | Main | Only unread or only read messages. One of: `any`, `unread`, `read` |
| Label | `label` | Text | No |  | Main | Only messages with this label, e.g. INBOX or Invoices. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains these words; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | Main | Only messages with attachments. |
| Query | `query` | Text | No |  | More options | Gmail search text; all messages when empty. Example: `from:billing@acme.com has:attachment newer_than:7d` |
| Maximum results | `max_results` | Number | No | `100` | More options | Maximum messages returned. |
| Include spam trash | `include_spam_trash` | Yes/No | No | No | More options | Also search Spam and Trash. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, thread_id, from, to, subject, date, snippet, unread, labels. |
| `count` | Number | Number of messages. |

*Time limit 300 s; safe to repeat; needs: network.*

### Change Gmail message status

`ooa.google/gmail_mark_message` (action)

Mark a Gmail message as read, unread, starred, not starred, important or not important.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Status | `status` | Text | No | `read` | Main | New status. One of: `read`, `unread`, `starred`, `unstarred`, `important`, `not_important` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move all matching Gmail messages

`ooa.google/gmail_move_all` (action)

Add and remove labels on every Gmail message that matches a search or the filters, e.g. move them from INBOX to Processed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Remove labels | `remove_labels` | Any | No | `INBOX` | Main | Label name(s) or id(s) to remove; INBOX by default. |
| Add labels | `add_labels` | Any | No |  | Main | Label name(s) or id(s) to add. |
| Status | `status` | Text | No | `any` | Main | Only unread or only read messages. One of: `any`, `unread`, `read` |
| Label | `label` | Text | No |  | Main | Only messages with this label, e.g. INBOX or Invoices. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains these words; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Query | `query` | Text | No |  | More options | Gmail search selecting the messages. Example: `from:billing@acme.com` |
| Maximum messages | `max_messages` | Number | No | `500` | More options | Upper limit of messages handled in one run. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of messages changed. |

*Time limit 600 s; needs: network.*

### Move Gmail message

`ooa.google/gmail_move_message` (action)

Move a Gmail message between labels, e.g. add Processed and remove INBOX.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Remove labels | `remove_labels` | Any | No | `INBOX` | Main | Label name(s) or id(s) to remove; INBOX by default, which archives the message. |
| Add labels | `add_labels` | Any | No |  | Main | Label name(s) or id(s) to add: text separated by , or a List. |
| Keep other labels | `keep_other_labels` | Yes/No | No | Yes | Main | When false, every label except the added ones and the read, starred and important marks is removed. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `labels` | List | Label ids the message has afterwards. |

*Time limit 60 s; safe to repeat; needs: network.*

### Reply all to Gmail message

`ooa.google/gmail_reply_all` (action)

Reply to the sender and every other recipient of a Gmail message in the same thread.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Cc | `cc` | Any | No |  | Main | More CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Exclude sender | `exclude_sender` | Yes/No | No | No | Main | Leave the original sender out and reply to the other recipients. |
| Subject | `subject` | Text | No |  | Main | New subject; Re: and the original subject when empty. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Reply text. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Include the original message | `include_original` | Yes/No | No | Yes | More options | Quote the original message below the reply. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Gmail id of the reply. |
| `thread_id` | Text | Conversation thread id. |

*Time limit 60 s; needs: network.*

### Reply to Gmail message

`ooa.google/gmail_reply_message` (action)

Reply to the sender of a Gmail message in the same conversation thread.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Cc | `cc` | Any | No |  | Main | More CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Reply text. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Include the original message | `include_original` | Yes/No | No | Yes | More options | Quote the original message below the reply. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Gmail id of the reply. |
| `thread_id` | Text | Conversation thread id. |

*Time limit 60 s; needs: network.*

### Save attachments of all matching Gmail messages

`ooa.google/gmail_save_all_attachments` (action)

Save the attachments of every Gmail message that matches a search or the filters, optionally marking them as read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Status | `status` | Text | No | `any` | Main | Only unread or only read messages. One of: `any`, `unread`, `read` |
| Label | `label` | Text | No |  | Main | Only messages with this label, e.g. INBOX or Invoices. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains these words; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Query | `query` | Text | No |  | More options | Gmail search selecting the messages; all messages with attachments when empty. Example: `from:billing@acme.com` |
| Mark read | `mark_read` | Yes/No | No | No | More options | Mark each matching message as read afterwards. |
| Maximum messages | `max_messages` | Number | No | `100` | More options | Upper limit of messages handled in one run. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |
| `message_count` | Number | Number of messages that matched. |

*Time limit 600 s; needs: network, filesystem.*

### Save Gmail attachments

`ooa.google/gmail_save_attachments` (action)

Save a Gmail message's attachments to a folder, optionally only names matching a pattern such as *.pdf.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |

*Time limit 60 s; needs: network, filesystem.*

### Save Gmail message as .eml

`ooa.google/gmail_save_message_as_eml` (action)

Save a complete Gmail message, with attachments, as an .eml file that mail programs can open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Message ID | `message_id` | Text | Yes |  | Main | Gmail message id from List Gmail messages. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save the .eml file; a path ending in / or an existing folder gets a file named after the subject. Example: `mail/message.eml` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing file; when false a number such as (2) is added to the name. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 60 s; needs: network, filesystem.*

### Send Gmail

`ooa.google/gmail_send` (action)

Send a plain-text or HTML email from Gmail with optional CC, BCC, attachments, reply-to and send-as alias.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| To | `to` | Any | Yes |  | Main | Recipient address(es). |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Subject | `subject` | Text | Yes |  | Main | Subject. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach (the whole email up to 25 MB). |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Body | `body` | Text | No | (empty) | Main | Body. |
| From | `from` | Text | No |  | More options | Send from this address, which must be one of the account's Gmail send-as aliases. |
| Reply to | `reply_to` | Any | No |  | More options | Address(es) that replies go to, when not the sender. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Gmail message id. |

*Time limit 60 s; needs: network.*

## Sheets

### Add sheet

`ooa.google/sheets_add_sheet` (action)

Add a new empty sheet (tab) to a spreadsheet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | Yes |  | Main | Name for the new sheet. |
| Position | `position` | Number | No |  | Main | Tab position starting at 1; added last when empty. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sheet_id` | Number | Numeric id of the new sheet. |

*Time limit 60 s; needs: network.*

### Append rows to sheet

`ooa.google/sheets_append` (action)

Add a table's rows after the last row of a sheet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | Sheet (and table start) to append to. Example: `Sheet1!A1` |
| Table | `table` | Table | Yes |  | Main | Rows to append (column names are not written). |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `updated_range` | Text | Range that received the rows. |

*Time limit 60 s; needs: network.*

### Autofit columns

`ooa.google/sheets_autofit_columns` (action)

Resize columns to fit their content.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| From column | `from_column` | Text | No |  | Main | First column (letter or number); all columns when empty. |
| To column | `to_column` | Text | No |  | Main | Last column (letter or number); the first column when only that is given. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Autofit rows

`ooa.google/sheets_autofit_rows` (action)

Resize rows to fit their content.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| From row | `from_row` | Number | No |  | Main | First row from 1; all rows when empty. |
| To row | `to_row` | Number | No |  | Main | Last row; the first row when only that is given. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Clear range

`ooa.google/sheets_clear_range` (action)

Remove the values in a range while keeping its formatting and the cell layout.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Example: `Sheet1!A2:D100` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Copy range

`ooa.google/sheets_copy_range` (action)

Copy a range to another place, in the same or another sheet of the spreadsheet: values, formats, formulas or everything.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Source | `source` | Text | Yes |  | Main | Range to copy. A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Example: `Sheet1!A1:C10` |
| Destination | `destination` | Text | Yes |  | Main | Top-left cell (or range) to paste into, optionally with the sheet name. Example: `Sheet2!E1` |
| Paste | `paste` | Text | No | `all` | Main | What to paste. One of: `all`, `values`, `formats`, `formulas`, `no_borders` |
| Transpose | `transpose` | Yes/No | No | No | Main | Turn rows into columns. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Create spreadsheet

`ooa.google/sheets_create_spreadsheet` (action)

Create a new Google spreadsheet, optionally with named sheets. Other spreadsheet actions accept its id, its link or its name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Title | `title` | Text | Yes |  | Main | Spreadsheet title. |
| Sheet names | `sheet_names` | List | No |  | Main | Names of the sheets to create; one default sheet when empty. |
| Folder ID | `folder_id` | Text | No |  | Main | Drive folder to put the spreadsheet in; My Drive when empty. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `spreadsheet_id` | Text | Id of the new spreadsheet. |
| `url` | Text | Link to open the spreadsheet. |

*Time limit 60 s; needs: network.*

### Delete columns

`ooa.google/sheets_delete_columns` (action)

Delete columns starting at a column; columns to the right move left.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Column | `column` | Text | Yes |  | Main | First column to delete (letter or number from 1). Example: `C` |
| Count | `count` | Number | No | `1` | Main | Number of columns. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Delete range

`ooa.google/sheets_delete_range` (action)

Delete the cells of a range and shift the neighbouring cells up or left to fill the gap.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Example: `Sheet1!B2:C4` |
| Shift | `shift` | Text | No | `up` | Main | Direction the remaining cells move. One of: `up`, `left` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Delete rows

`ooa.google/sheets_delete_rows` (action)

Delete rows starting at a row number; rows below move up.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Row | `row` | Number | Yes |  | Main | First row to delete (1 = top). |
| Count | `count` | Number | No | `1` | Main | Number of rows. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Delete sheet

`ooa.google/sheets_delete_sheet` (action)

Delete a sheet (tab) and all its content.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Name of the sheet to delete; or give position instead. |
| Position | `position` | Number | No |  | Main; choice Sheet by = Position | Tab position of the sheet to delete, starting at 1; used instead of sheet. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Find cells

`ooa.google/sheets_find_cells` (action)

Search a sheet or range for text and return the addresses of the matching cells.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | No |  | Main | Sheet name or range to search, e.g. Sheet1 or Sheet1!A1:F200; the first sheet when empty. |
| Text | `text` | Text | Yes |  | Main | Text to look for. |
| Match case | `match_case` | Yes/No | No | No | Main | Upper and lower case must match. |
| Whole cell | `whole_cell` | Yes/No | No | No | Main | The whole cell must equal the text, not just contain it. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cells` | List | A1 addresses of the matches, e.g. B7, in reading order. |
| `count` | Number | Number of matches. |

*Time limit 60 s; safe to repeat; needs: network.*

### Format cells

`ooa.google/sheets_format_cells` (action)

Apply bold, italic, font size, text and fill colours, alignment and number format to a range.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Example: `Sheet1!A1:D1` |
| Font family | `font_family` | Text | No |  | Main; tick Change the font | Font name; unchanged when empty. Example: `Roboto` |
| Font size | `font_size` | Number | No |  | Main; tick Change the font | Font size in points; unchanged when empty. |
| Bold | `bold` | Yes/No | No |  | Main; tick Change the font | Bold on or off; unchanged when empty. |
| Italic | `italic` | Yes/No | No |  | Main; tick Change the font | Italic on or off; unchanged when empty. |
| Underline | `underline` | Yes/No | No |  | Main; tick Change the font | Underline on or off; unchanged when empty. |
| Strikethrough | `strikethrough` | Yes/No | No |  | Main; tick Change the font | Strikethrough on or off; unchanged when empty. |
| Font color | `font_color` | Text | No |  | Main; tick Change the font | Text colour as #RRGGBB; unchanged when empty. Example: `#FFFFFF` |
| Horizontal alignment | `horizontal_alignment` | Text | No |  | Main; tick Change the alignment | Horizontal alignment; unchanged when empty. One of: `left`, `center`, `right` |
| Vertical alignment | `vertical_alignment` | Text | No |  | Main; tick Change the alignment | Vertical alignment; unchanged when empty. One of: `top`, `middle`, `bottom` |
| Wrap | `wrap` | Text | No |  | Main; tick Change text wrapping | How long text is shown; unchanged when empty. One of: `overflow`, `clip`, `wrap` |
| Merge | `merge` | Text | No |  | Main; tick Merge or unmerge | Merge the range (all, each row or each column) or unmerge it; unchanged when empty. One of: `all`, `rows`, `columns`, `unmerge` |
| Background color | `background_color` | Text | No |  | More options | Fill colour as #RRGGBB; unchanged when empty. Example: `#1F4E79` |
| Number format | `number_format` | Text | No |  | More options | Number format pattern such as #,##0.00, 0% or yyyy-mm-dd; unchanged when empty. Example: `#,##0.00` |
| Number format type | `number_format_type` | Text | No | `NUMBER` | More options | Kind of number format the pattern describes. One of: `NUMBER`, `CURRENCY`, `PERCENT`, `DATE`, `TIME`, `DATE_TIME`, `SCIENTIFIC`, `TEXT` |
| Border style | `border_style` | Text | No |  | More options | Style of every border of the range, inside and out; unchanged when empty. One of: `none`, `solid`, `solid_medium`, `solid_thick`, `dashed`, `dotted`, `double` |
| Border color | `border_color` | Text | No |  | More options | Border colour as #RRGGBB; black when empty. Example: `#000000` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get cell value

`ooa.google/sheets_get_cell` (action)

Read the value of one cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Cell | `cell` | Text | Yes |  | Main | One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2. Example: `Sheet1!B2` |
| Formatted | `formatted` | Yes/No | No | No | Main | Return the text as displayed (e.g. 12.5%) instead of the raw value. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Cell value (number, text or boolean); empty when the cell is blank. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get cell colour

`ooa.google/sheets_get_cell_color` (action)

Read the fill and text colour of one cell as #RRGGBB or as the nearest basic colour name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Cell | `cell` | Text | Yes |  | Main | One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2. Example: `Sheet1!B2` |
| Format | `format` | Text | No | `hex` | Main | hex gives #RRGGBB; name gives the nearest of black, white, grey, red, orange, yellow, green, cyan, blue, purple, magenta, pink or brown. One of: `hex`, `name` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `background_color` | Text | Fill colour, #FFFFFF (white) when none is set. |
| `font_color` | Text | Text colour, #000000 (black) when none is set. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get range values

`ooa.google/sheets_get_range` (action)

Read a range as a list of rows and, optionally, as a data table with the first row as column names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | No |  | Main; choice Read = A range | A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Leave empty to read all cells of a sheet. Example: `Sheet1!A1:D20` |
| Sheet | `sheet` | Text | No |  | Main; choice Read = All cells of a sheet | When no range is given: the sheet (tab) whose cells are all read; the first sheet when empty. |
| First row is the header | `header` | Yes/No | No | Yes | Main | Table only: the first row holds column names. |
| Formatted | `formatted` | Yes/No | No | No | Main | Return values as displayed instead of raw values. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | List of rows, each a list of cell values. |
| `table` | Table | The same range as a data table. |

*Time limit 60 s; safe to repeat; needs: network.*

### Hide sheet

`ooa.google/sheets_hide_sheet` (action)

Hide a sheet (tab) from view without deleting it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | Yes |  | Main | Sheet to hide. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Insert columns

`ooa.google/sheets_insert_columns` (action)

Insert empty columns before a column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Column | `column` | Text | Yes |  | Main | The new columns are inserted before this column (letter or number from 1). Example: `C` |
| Count | `count` | Number | No | `1` | Main | Number of columns. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Insert range

`ooa.google/sheets_insert_range` (action)

Insert empty cells at a range, pushing the existing cells down or right.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named. Example: `Sheet1!B2:C4` |
| Shift | `shift` | Text | No | `down` | Main | Direction the existing cells move. One of: `down`, `right` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### Insert rows

`ooa.google/sheets_insert_rows` (action)

Insert empty rows before a row number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Row | `row` | Number | Yes |  | Main | The new rows are inserted before this row (1 = top). |
| Count | `count` | Number | No | `1` | Main | Number of rows. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; needs: network.*

### List sheets

`ooa.google/sheets_list_sheets` (action)

List the sheets (tabs) of a spreadsheet with their size and visibility.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Exclude hidden | `exclude_hidden` | Yes/No | No | No | Main | Leave hidden sheets out. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: sheet_id, name, index, hidden, rows, columns. |
| `names` | List | Sheet names in tab order. |
| `count` | Number | Number of sheets listed. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read sheet range

`ooa.google/sheets_read` (action)

Read a range such as Sheet1!A1:D100, or all cells of a sheet, into a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | No |  | Main; choice Read = A range | A1 range; leave empty to read all cells of a sheet. Example: `Sheet1!A1:D100` |
| Sheet | `sheet` | Text | No |  | Main; choice Read = All cells of a sheet | When no range is given: the sheet (tab) whose cells are all read; the first sheet when empty. |
| First row is the header | `header` | Yes/No | No | Yes | Main | First row holds column names. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Range content. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read column

`ooa.google/sheets_read_column` (action)

Read all values of one column as a list.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Column | `column` | Text | Yes |  | Main | Column letter or number from 1. Example: `B` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values from row 1 to the last filled row. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read formula

`ooa.google/sheets_read_formula` (action)

Read the formula of one cell (the plain value when the cell has no formula).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Cell | `cell` | Text | Yes |  | Main | One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2. Example: `Sheet1!D2` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `formula` | Text | Formula text such as =SUM(B2:C2). |

*Time limit 60 s; safe to repeat; needs: network.*

### Read row

`ooa.google/sheets_read_row` (action)

Read all values of one row as a list.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Row | `row` | Number | Yes |  | Main | Row number (1 = top). |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values from column A to the last filled column. |

*Time limit 60 s; safe to repeat; needs: network.*

### Rename sheet

`ooa.google/sheets_rename_sheet` (action)

Give a sheet (tab) a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Current sheet name; or give position instead. |
| Position | `position` | Number | No |  | Main; choice Sheet by = Position | Tab position of the sheet to rename, starting at 1; used instead of sheet. |
| New name | `new_name` | Text | Yes |  | Main | New sheet name. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get used row count

`ooa.google/sheets_row_count` (action)

Count the rows and columns in use on a sheet, up to the last filled cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main | Sheet (tab) name; the first sheet when empty. |
| Non empty only | `non_empty_only` | Yes/No | No | No | Main | Count only rows that hold a value, instead of the number of the last filled row. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows` | Number | Number of the last row that holds a value. |
| `columns` | Number | Number of the last column that holds a value. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set cell value

`ooa.google/sheets_set_cell` (action)

Write a value into one cell; text is interpreted as if typed by a user.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Cell | `cell` | Text | Yes |  | Main | One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2. Example: `Sheet1!B2` |
| Value | `value` | Any | Yes |  | Main | Value to write; empty clears the cell. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set formula

`ooa.google/sheets_set_formula` (action)

Put a formula into one cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Cell | `cell` | Text | Yes |  | Main | One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2. Example: `Sheet1!D2` |
| Formula | `formula` | Text | Yes |  | Main | Formula; a leading = is added when missing. Example: `=SUM(B2:C2)` |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Show sheet

`ooa.google/sheets_show_sheet` (action)

Make a hidden sheet (tab) visible again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | Yes |  | Main | Sheet to show. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

*Time limit 60 s; safe to repeat; needs: network.*

### Write to sheet

`ooa.google/sheets_write` (action)

Write a table into a sheet starting at a cell, overwriting existing values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Range | `range` | Text | Yes |  | Main | Top-left cell or range. Example: `Sheet1!A1` |
| Table | `table` | Table | Yes |  | Main | Data to write. |
| Include header | `include_header` | Yes/No | No | Yes | Main | Write column names first. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `updated_cells` | Number | Cells written. |

*Time limit 60 s; needs: network.*

### Write data table

`ooa.google/sheets_write_data_table` (action)

Write a data table to a sheet from a start cell, optionally clearing the sheet first so no old rows remain.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Google OAuth access token from the vault. |
| Spreadsheet ID | `spreadsheet_id` | Text | Yes |  | Main | The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names). |
| Sheet | `sheet` | Text | No |  | Main; choice Worksheet = A named sheet | Sheet (tab) name; the first sheet when empty. |
| Table | `table` | Table | Yes |  | Main | Data to write. |
| Start cell | `start_cell` | Text | No | `A1` | Main | Top-left cell of the data. |
| Include header | `include_header` | Yes/No | No | Yes | Main | Write column names as the first row. |
| Clear sheet first | `clear_sheet_first` | Yes/No | No | No | More options | Remove all values on the sheet before writing. |
| Endpoint override | `endpoint_override` | Text | No |  | More options | Replaces https://*.googleapis.com for testing or a proxy; leave empty normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `updated_range` | Text | Range that received the data. |
| `updated_cells` | Number | Cells written. |

*Time limit 60 s; needs: network.*
