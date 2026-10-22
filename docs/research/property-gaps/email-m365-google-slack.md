# Property gaps: Email, Microsoft 365, Google Workspace and Slack

Compared on 2026-10-22 against the public Automation 360 documentation (docs.automationanywhere.com, `/r/automation-360/...` pages; no sign-in).
Every AA option below is described in our own words. "Agent" means the automation that runs the steps.

Rules followed:
- New inputs are optional, and their defaults keep the old behaviour.
- No existing input was renamed or removed.
- No new dependencies were added. Cloud calls use HttpJson, which runs on the JDK HttpClient; email uses Jakarta Mail (Angus).

In the tables:
- **Had** is what the action already offered before this pass.
- **Added** lists the new inputs (`snake_case`) and outputs.
- **Skipped** lists AA options we did not add, and why.

## Summary

| Toolkit | Actions reviewed | Actions with new options | New options (inputs + outputs) | New actions |
|---|---|---|---|---|
| Email (`ooa.email`) | 17 | 13 | 40 | 1 |
| Microsoft 365 (`ooa.microsoft365`) | 79 | 22 | 85 | 10 |
| Google Workspace (`ooa.google`) | 61 | 18 | 57 | 7 |
| Slack (`ooa.slack`) | 8 | 5 | 18 | 2 |
| **Total** | **165** | **58** | **200** | **20** |

**Actions reviewed** counts our actions before this pass.

**New options** counts new inputs and outputs on existing actions. It does not count:
- the inputs of new actions;
- new keys inside dictionary outputs (for example the new keys on Read email).

## Points that apply to many actions

These are listed once here rather than on every row:
- **Session model.** AA's Connect/Disconnect sessions and "inside a loop" actions are covered in two ways:
  - Email has named sessions.
  - Cloud actions take a token per step.
  - In both cases, loop processing works by running Find emails (or List messages) and looping over the returned table, then passing the `id` to the per-email actions.
- **Credentials.** AA's credential modes (Basic, OAuth2 PKCE, client credentials, Control Room managed connections) map to our `password` / `access_token` inputs taken from the vault. The OAuth flow itself is handled by the vault and connection setup, not by the actions.
- **HTML editor.** AA's "HTML design editor" body format is a Studio editor. Our `html` boolean covers the "HTML code" choice.
- **"Raise error if attachment missing".** Our actions always fail with `FILE.NOT_FOUND`. That is the safe default, so no switch was added.
- **"Go Green" footer.** This is a fixed marketing footer, so it was skipped.

## Email (`ooa.email`): IMAP, POP3, SMTP

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| connect | Connect | Connect to Outlook, an EWS server, or an email server (IMAP or POP3, SSL/TLS, host, port); timeout; shared mailboxes (Microsoft 365, IMAP/EWS with OAuth) | IMAP over SSL, host, port, password or OAuth token, session | `protocol` (imap/pop3), `security` (ssl/starttls/none), `shared_mailbox` (opened as `user\shared` with a password, or as the shared address with OAuth), `timeout_seconds`. When the port is left at 993, the usual port for the chosen protocol and security is used. | **Outlook desktop:** Windows-only COM; already in the catalog as planned. **EWS:** Jakarta Mail has no EWS, and Microsoft retires EWS for Exchange Online in October 2026, so EWS-style mailboxes are handled by `ooa.microsoft365` over Graph (shared mailbox via `mailbox`, send as via `from`). **Exchange version / Azure cloud pickers:** EWS-only. |
| disconnect | Disconnect | Session | Session | - | - |
| send | Send | To, Cc, Bcc, Reply-To, subject, message, importance (high/normal/low), plain or HTML, attachments, encoding, From address, SSL/TLS, authentication | To, Cc, Bcc, subject, body, HTML, attachments, From, security | `importance`, `reply_to`, `read_receipt`, `inline_images` (embedded with a Content-ID so the HTML can use `cid:` names) | **Encoding choice:** we always send UTF-8, which every modern client reads. |
| forward | Forward | To, Cc, Bcc, Reply-To, extra attachments, importance, plain or HTML, message | To, Cc, note, HTML, inline or as .eml | `bcc`, `attachments`, `importance`, `reply_to` | - |
| reply | Reply | Cc, Bcc, Reply-To, attachments, importance, plain or HTML, message with the original below | Body, HTML, quote original, attachments | `cc`, `bcc`, `importance`, `reply_to` | - |
| reply_all | Reply All | Same as Reply, plus exclude the sender and an optional new subject | Same as reply | `cc`, `bcc`, `importance`, `reply_to`, `exclude_sender` | **New subject:** the Re: subject is needed for threading in most mail clients; Gmail's reply-all has this option. |
| mark | Change status | Read or unread (no effect on POP3) | Read/unread | `flagged` (set or clear the follow-up flag). POP3 now gets a clear `EMAIL.NOT_SUPPORTED` error. | - |
| folder_exists | Check if folder exists | Folder name; returns true or false | Same | - | - |
| delete | Delete | Deletes the current email (no permanent option) | Moves to Trash/Deleted Items | `permanent` | - |
| delete_all | Delete all | Folders, read/unread/both, subject contains (several values with ;), senders (several with ;), received on/after and before | Folder, unread, sender, subject, since, before, permanent | `read_only`, `to_contains`, `body_contains`. `from_contains` and `subject_contains` now accept several values separated by `;`. | **Several folders in one call:** run the action once per folder. |
| move | Move | Destination folder path | Destination | `create_folder` | - |
| move_all | Move all | Destination, read/unread/both, source folder, subject, senders, dates | Destination, folder, unread, sender, subject, dates | `read_only`, `to_contains`, `body_contains`, `create_folder`; `;` lists | - |
| save_attachments | Save attachments | Folder, overwrite (otherwise a number is added); embedded images not saved | Folder, name pattern | `overwrite`, `include_inline` | - |
| *(new)* save_all_attachments | Save all attachments | Read/unread/both, keep status, folder, save-to folder, overwrite | - | New action with folder and filters, name pattern, `overwrite`, `mark_read` (default keeps the status) | - |
| save_email_as_eml | Save email | Folder, optional file name (defaults to the subject), overwrite (otherwise numbered), EML only | Full path | `overwrite` (default true, as before). A folder path now gets a file named after the subject. | - |
| list (Find emails) | For each mail in mail box (loop) | Read/unread/both, keep status, newest or oldest first, folder, subject (`;` list), senders, received from/to, body format, local time zone | Folder, unread, sender, subject, since, max | `before`, `read_only`, `to_contains`, `body_contains`, `has_attachments_only`, `oldest_first`; `;` lists | **Local time zone switch:** times are UTC datetimes; ooa.datetime converts them. |
| get (Read email) | (loop dictionary) | Subject, sender, body, number of attachments and similar fields | from, to, cc, subject, received, text, html, attachments | Output keys `reply_to`, `sent`, `message_id`, `importance`, `flagged`, `size` | - |
| get_folders | - | (no AA equivalent) | List folders | - | - |

**POP3.** Find, read, save attachments, save .eml and delete all work. Ids are the message numbers in the session, and deletions happen when the session is disconnected. Change status, move and move all report `EMAIL.NOT_SUPPORTED`, matching AA's note that these do not apply to POP3.

## Microsoft 365 (`ooa.microsoft365`): Microsoft Graph

### Outlook

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| send_mail | Send | From (shared or other mailbox), To, Cc, Bcc, subject, attachments, plain or HTML, message | To, Cc, Bcc, subject, HTML, attachments, mailbox | `from` (send as), `importance`, `reply_to`, `save_to_sent_items`, `read_receipt` | **Files from cloud file variables:** attachments are local paths. |
| forward_mail | Forward | From, To, Cc, Bcc, attachments, body format, message | To, comment | `cc`, `bcc`, `attachments` (sent through a draft: create, attach, send), `from`, `importance` | - |
| reply_mail | Reply | From, Cc, Bcc, attachments, body format, message | Comment, reply all | `cc`, `bcc`, `attachments`, `from`, `importance`, `exclude_sender` (reply all) | - |
| reply_all_mail | Reply all | Same as Reply, plus exclude the sender | Comment | Same as reply_mail | - |
| mark_mail | Change status | Read or unread | Read, unread, flagged, unflagged, complete | - (already more than AA) | - |
| mail_folder_exists | Check if folder exists | Folder name, not case-sensitive; nested folders with a backslash | Top-level display name | Paths such as `Inbox/Invoices` or `Inbox\Invoices` are resolved level by level | - |
| delete_mail | Delete | Current email | Soft or permanent | - | - |
| delete_all_mail | Delete all | Folders, read/unread/both, subject (exact with quotes), senders, received on/after and before | Folder, unread, sender, subject, permanent, max | `read_only`, `since`, `before`; `;` lists; folder paths | **Exact match with quotes:** use the free `contains` text. **Several folders:** one call per folder. |
| move_mail | Move | Destination path such as `Inbox/finance/April` | Folder id or well-known name | Destination may be a path | - |
| move_all_mail | Move all | Destination, read/unread/both, source, filters, dates | Destination, folder, unread, sender, subject, max | `read_only`, `since`, `before`; `;` lists; paths | - |
| save_mail_attachments | Save attachments | Folder, overwrite (on by default in AA) | Folder, name pattern | `overwrite` (default off, as before), `include_inline` | - |
| *(new)* save_all_mail_attachments | Save all attachments | Read/unread/both, folders, filters, dates, save-to folder, overwrite | - | New action with filters, `overwrite`, `mark_read`, `max_results` | - |
| save_mail_as_eml | Save email | Folder, file name (defaults to the subject), overwrite, EML or MSG | Path | `overwrite`; a folder path gets a file named after the subject | **MSG format:** Graph can only export MIME (.eml), and writing .msg would need a compound-file library. |
| list_mail | (loop) | Same filters as the Email loop | Folder, unread, sender, subject, max | `read_only`, `since`, `before`, `has_attachments_only`, `oldest_first`; `;` lists; paths. The server filter starts with the date so Graph can sort. | - |
| get_mail | (loop dictionary) | Email properties | from, to, subject, received, body | `body_as_text`; output keys `cc`, `bcc`, `reply_to`, `sent`, `importance`, `flag_status`, `categories`, `conversation_id`, `internet_message_id` | - |
| *(n/a)* | Save email to file stream, Assign attachment(s) | Hand files to other packages without downloading them | - | - | **AA stream variables:** we use local files and paths. |

### Calendar

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| create_event | Create meeting | Title, required and optional attendees, start/end, time zone, all day, location, body (HTML), Teams meeting, recurrence, reminder, show as, importance, sensitivity, categories | Title, times, time zone, attendees, body, location | `optional_attendees`, `all_day`, `online_meeting` (output `online_meeting_url`), `html`, `importance`, `show_as`, `sensitivity`, `reminder_minutes`, `categories`, `recurrence` (daily/weekly/monthly/yearly), `recurrence_interval`, `recurrence_end`, `recurrence_count` | **Complex recurrence patterns** (for example "second Tuesday of the month"): use update_event or the Graph API through ooa.rest. |
| update_event | Modify meeting | The same fields as Create meeting | Title, times, body, location | `html`, `importance`, `show_as`, `sensitivity`, `reminder_minutes`, `categories` | - |
| list_events | (get meetings) | Period, calendar | Period | `subject_contains`, `time_zone`, `calendar_id` | - |
| cancel_event | Cancel meeting | Note to attendees | Same | - | - |
| delete_event | Delete meeting information | Event | Same | - | - |
| respond_to_event | Respond to meeting | Accept / tentative / decline, note, send response | Same | - | - |
| add_attendees, remove_attendees | Add / delete attendee | Addresses, type | Same | - | - |
| add_event_attachment | Add attachment | File | Same | - | - |
| find_meeting_times | Get available meeting slot(s) | Attendees, period, length | Same | - | - |

### OneDrive / SharePoint files

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| upload_file | Upload file | Target folder, behaviour when the name exists | Path, drive | `if_exists` (replace by default / rename / fail) | **Upload sessions over 250 MB:** still out of scope, with a clear error. |
| download_file | Download file | Local folder, overwrite | Path | `overwrite` (default true) | - |
| copy_item | Copy file or folder | Destination, new name, conflict behaviour | Destination, name, other drive, wait | `if_exists` | - |
| move_item | Move file or folder | Destination, conflict behaviour | Destination, name | `if_exists` | - |
| rename_item | Rename file or folder | New name | Same | - | - |
| delete_item | Delete file or folder | Item | Recycle bin | `permanent` | - |
| create_folder | Create folder | Parent, name, conflict | Same | - | - |
| find_items | Find files and folders | Text, files or folders, folder to search | Text, max | `item_type`, `in_folder`, `exact_name` | - |
| get_item_info | Get file or folder information | Item | Same | - | - |
| check_permissions, list_permissions | Check permission | Person and access | Same | - | - |
| *(new)* share_item | Assign file | Give people access to a file | - | New action: `recipients`, `role` (read/write), `send_invitation`, `message`, `require_sign_in` | - |
| *(new)* create_sharing_link | (sharing) | Sharing link | - | New action: `type` (view/edit), `scope` (organization/anonymous), `expires` | - |
| export_to_pdf | Export to PDF | Item, target | Same | - | - |
| restore_version | Restore last version | Item | Plus a chosen version | - | - |
| list_files | - | - | - | - | - |

### Excel online

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| excel_format_range | Format cell / Set cell color | Font name, size, colour, bold, italic, underline, strikethrough, alignment (horizontal and vertical), wrap, borders, fill, number format, merge, row height, column width | Fill, bold, font colour, number format | `italic`, `underline`, `strikethrough`, `font_name`, `font_size`, `horizontal_alignment`, `vertical_alignment`, `wrap_text`, `column_width`, `row_height`, `border_style`, `border_color`, `merge` | - |
| *(new)* excel_hide_worksheet / excel_show_worksheet | Hide / Show worksheet | Sheet | - | New actions (hide has `very_hidden`) | - |
| *(new)* excel_find | Find | Text, match case, whole cell, range | - | New action | - |
| *(new)* excel_get_cell_format | Read cell format / Get cell or text color | Cell; fill and font colour, font and alignment | - | New action | - |
| *(new)* excel_rename_table | Rename table | Table, new name | - | New action | - |
| *(new)* excel_create_workbook | Create workbook | Path, sheet names | - | New action, building a minimal .xlsx in memory; `if_exists` defaults to fail | - |
| excel_get_worksheets, add/delete/rename_worksheet, get/set range and cell, formulas, clear/insert/delete range, autofit, used range, read row/column, tables, write data table, excel_read_range, excel_write_range | Get worksheet names, Create/Delete/Rename sheet, Get/Set cell, Get multiple cells, Read/Set cell formula, Delete/Insert cell and range, Autofit, Get row count, Read row/column, table actions, Write from data table, Get worksheet as data table | Mostly the address and the value; no extra options | Same | - | **Session ideas (Open, Close, Switch to sheet, Go to cell, Go to next empty cell, Get cell address / column name / row number):** our actions are stateless and take the sheet and address directly; Get used range returns the last row and column, which gives the next empty cell. **Copy worksheet:** not in Graph v1.0. **Paste cell:** use Get and Set range. |

### Teams

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| teams_message | Send channel message | Team, channel, text or HTML, subject, importance, reply in a thread | Team, channel, message, HTML | `subject`, `importance` (normal/high/urgent), `reply_to_message_id` | **@mentions:** these need member ids and tags in the HTML, and can be written as HTML today. |
| send_chat_message | Send chat message | Chat, text or HTML, importance | Chat, message, HTML | `importance` | - |
| *(new)* get_channel_details | Get channel details | Team, channel | - | New action | - |
| list_teams, get_team_by_name, list_channels, get_channel_by_name, get_team_details, get_user, create_chat | Get team by name, Get team details, Get channel by name, Get user details | Name or id | Same | - | - |

### SharePoint lists

`list_lists`, `get_list_items`, `create_list_item`, `update_list_item` and `delete_list_item` have no AA counterpart in these packages, so they were not changed.

## Google Workspace (`ooa.google`)

### Gmail

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| gmail_send | Send | To, Cc, Bcc, attachments, plain or HTML, message | To, subject, body, HTML (no attachments) | `cc`, `bcc`, `attachments`, `from` (send-as alias), `reply_to` | - |
| gmail_forward_message | Forward | To, Cc, Bcc, attachments, body format, message | To, Cc, note, HTML, inline or .eml, include attachments | `bcc`, `attachments` | - |
| gmail_reply_message | Reply | Cc, Bcc, attachments, body format | Body, HTML, quote, attachments | `cc`, `bcc` | - |
| gmail_reply_all | Reply all | Additional Cc, Bcc, exclude sender, new subject, attachments | Same as reply | `cc`, `bcc`, `exclude_sender`, `subject` | - |
| gmail_mark_message | Change status | Read or unread | Plus starred and important | - | - |
| gmail_delete_message | Delete | Moves to Trash | Trash or permanent | - | - |
| gmail_delete_all | Delete all | Read/unread/both, labels, subject, senders, received after/before | Gmail query (required), permanent, max | `from_contains`, `subject_contains`, `since`, `before`, `status`, `label`. The query becomes optional when a filter is given; at least one is still required. | - |
| gmail_move_message | Move email | Labels to remove and add, keep other labels | Add and remove labels | `keep_other_labels` | - |
| *(new)* gmail_move_all | Move all | Labels to remove and add, keep others, read/unread/both, labels, subject, senders, dates | - | New action using batchModify; a query or filter is required | - |
| gmail_save_attachments | Save attachment | Folder, overwrite (otherwise numbered) | Folder, pattern | `overwrite` | - |
| *(new)* gmail_save_all_attachments | Save all attachments | Read/unread/both, labels, filters, folder, overwrite | - | New action with query and filters, `overwrite`, `mark_read` | - |
| gmail_save_message_as_eml | Save email | Folder, optional file name, overwrite (otherwise numbered) | Path | `overwrite`; a folder path gets a file named after the subject | - |
| gmail_list_messages | (Gmail loop) | Read/unread/both, newest or oldest, folder or label, subject, senders, dates | Gmail query, max, spam/trash | `from_contains`, `subject_contains`, `since`, `before`, `status`, `label`, `has_attachments_only` (combined with the query; dates use exact seconds) | **Oldest first:** the Gmail API returns newest first only. |
| gmail_get_message | (loop dictionary) | Properties | Full details | - | - |
| *(new)* gmail_label_exists | Check if label exists (If condition) | Label name | - | New action | - |

### Drive

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| drive_upload | Upload file | Source file, target folder, overwrite an existing file | File, folder, name | `overwrite` (replaces the content and keeps the file id), `description` | **Streaming from cloud file variables:** we use local paths. |
| drive_download | Download file | File, local path, overwrite, rename | File, path | `overwrite` (default true) | **Rename:** `local_path` sets the name. |
| *(new)* drive_copy_folder | Copy folder | Source folder, destination | - | New action that copies subfolders and files recursively | - |
| *(new)* drive_check_permission | Check permissions | Read / write / delete check | - | New action: user, group, domain or anyone grants; `access` read/comment/write | **Delete:** the Drive API has no separate delete right. Owner and organizer show up as the role. |
| drive_create_permission | Create file permission | Role; user, group, domain or anyone; email or domain | Same, plus notify and message | `expires` | - |
| *(new)* drive_update_permission | Update file permission | Permission id, role | - | New action | - |
| drive_delete_permission, drive_list_permissions | Delete / Get file permission | Permission | Same | - | - |
| drive_copy_file, drive_move_item, drive_rename_item, drive_delete_item, drive_create_folder, drive_find_items, drive_get_file_info, drive_list, drive_download_export | Copy file (with rename), Move file/folder, Rename file/folder, Delete file/folder, Create folder, Find file/folder (exact or contains), Get file information | Path or id | Same (by id; find covers exact and contains) | - | **Addressing by path** (`MyFolder/MyFile.doc`): ids are stable and unambiguous in Drive, where names can repeat; find gives the id. |
| *(n/a)* | Assign file, Open file / folder | Stream variable; open in a browser | - | - | Stream variables and browser UI are AA-specific. |

### Sheets

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| sheets_format_cells | Format cells, Set cell or text color | Font (name, size, colour), horizontal and vertical alignment, wrap, merge; cell or text colour | Bold, italic, size, colours, horizontal alignment, number format | `underline`, `strikethrough`, `font_family`, `vertical_alignment`, `wrap`, `merge`, `border_style`, `border_color` | **Colour by name:** colours are #RRGGBB. |
| *(new)* sheets_copy_range | Paste cell | Source and target cell | - | New action: `paste` (all/values/formats/formulas/no_borders), `transpose` | - |
| sheets_create_spreadsheet | Create workbook | Name, Drive folder | Title, sheet names | `folder_id` | - |
| sheets_list_sheets | Get all sheet names / Retrieve sheets count | Exclude hidden; count | Names and table | `exclude_hidden`; output `count` | - |
| sheets_row_count | Get number of rows | Non-empty rows or total rows | Last filled row | `non_empty_only` | - |
| Other sheets_* actions | Create/Delete/Rename/Hide/Show sheet, Get/Set cell, Get multiple cells, Read row/column, Insert/Delete row, column and range, Set/Read formula, Get cell colour, Autofit, Write from data table | Address and value | Same | - | **Session ideas (Open, Close, Activate sheet, Go to cell, Get current sheet name, active-cell options):** our actions are stateless. **Read row/column from a start cell:** use Get range values. |

### Calendar

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| calendar_create_event | Create event | Title, location, attendees, dates, all day or times, time zones, recurring, visibility, description | Title, times, time zone, attendees, description, calendar | `location`, `all_day`, `optional_attendees`, `notify`, `add_meet_link` (output `meet_link`), `reminder_minutes`, `visibility`, `show_as`, `recurrence` (word or RRULE) | **Separate start and end time zones:** one zone covers both. |
| calendar_update_event | (no AA action) | - | Title, times, description, location, attendees, notify | `visibility`, `reminder_minutes` | - |
| calendar_delete_event, calendar_list_events | Delete event (AA has no list) | Event id | Same | - | - |
| sheets_read, sheets_write, sheets_append, drive_list | (older simple actions) | - | - | - | Covered by the newer actions. |

## Slack (`ooa.slack`)

| Our action | AA action | AA options (our words) | Had | Added | Skipped / why |
|---|---|---|---|---|---|
| post_message | Send channel message | Channel, message, reply in thread, files, formatting | Channel, text, thread | `reply_broadcast`, `blocks` (Block Kit list or JSON), `unfurl_links`, `unfurl_media`, `mrkdwn`, `username`, `icon_emoji`, `files` (Slack's external upload flow) | - |
| send_direct_message | Send direct message | User, message, files | User (id or email), text | `thread_ts`, `blocks`, `unfurl_links`, `unfurl_media`, `mrkdwn`, `files`; several users open a group conversation | - |
| update_message | - | - | Channel, ts, text | `blocks` | - |
| *(new)* upload_file | (files on messages) | Share files in a conversation | - | New action: `files`, `comment`, `thread_ts` | - |
| get_conversation_history | Get conversation details | Conversation, messages | Channel, oldest/latest, max | `thread_ts` (reads the replies of a thread), `inclusive` | - |
| get_channel_id_by_name, get_channel_details, get_user_details | Get channel ID by name, Get channel details, Get user details | Name, id or email | Same | - | - |
| *(new)* get_workspace_id_by_name | Get workspace ID by name | Workspace name | - | New action: searches the workspaces the app is installed in by name or domain, falling back to the token's own workspace | - |
| get_workspace_details | Get workspace details | Workspace | Own workspace | `workspace_id` | - |

## Tests

Each new option has a test:
- **Email:** `EmailOptionsTest` builds and parses messages in memory with Jakarta Mail.
- **Cloud toolkits:** `Microsoft365OptionsTest`, `GoogleOptionsTest` and `SlackOptionsTest` run the actions through the Runner against the local fake HTTP server from `ConnectorsTest`.
- **Existing tests:** where they call actions directly, they now pass the defaults for the new inputs.
