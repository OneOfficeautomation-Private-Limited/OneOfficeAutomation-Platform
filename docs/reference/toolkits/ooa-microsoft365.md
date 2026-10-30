# Microsoft 365

Toolkit `ooa.microsoft365`, version 1.0.0. Outlook mail, calendar, OneDrive and SharePoint files and Teams messages through Microsoft Graph.

[All toolkits](README.md)

## Contents

- **Calendar**: Add event attendees, Add event attachment, Cancel calendar event, Clear event details, Create calendar event, Delete calendar event, Find free meeting times, Get calendar events, Remove event attendees, Respond to meeting invitation, Update calendar event
- **Files**: Check file permission (OneDrive/SharePoint), Copy file or folder (OneDrive/SharePoint), Create folder (OneDrive/SharePoint), Create sharing link (OneDrive/SharePoint), Delete file or folder (OneDrive/SharePoint), Download file (OneDrive/SharePoint), Export file to PDF (OneDrive/SharePoint), Find files (OneDrive/SharePoint), Get file information (OneDrive/SharePoint), List files (OneDrive/SharePoint), List file permissions (OneDrive/SharePoint), Move file or folder (OneDrive/SharePoint), Rename file or folder (OneDrive/SharePoint), Restore file version (OneDrive/SharePoint), Share file or folder (OneDrive/SharePoint), Upload file (OneDrive/SharePoint)
- **Teams**: Create one-on-one chat (Teams), Get channel by name (Teams), Get channel details (Teams), Get team by name (Teams), Get team details (Teams), Get user (Microsoft 365), List channels (Teams), List teams (Teams), Send chat message (Teams), Post Teams message
- **SharePoint**: Create SharePoint list item, Delete SharePoint list item, Get SharePoint list items, List SharePoint lists, Update SharePoint list item
- **Mail**: Delete all emails (Outlook), Delete email (Outlook), Forward email (Outlook), Read email (Outlook), Find emails (Outlook), Check mail folder exists (Outlook), Change email status (Outlook), Move all emails (Outlook), Move email (Outlook), Reply all to email (Outlook), Reply to email (Outlook), Save attachments of all matching emails (Outlook), Save email as EML (Outlook), Save email attachments (Outlook), Send email (Outlook)
- **Excel online**: Add table rows (Excel online), Add worksheet (Excel online), Autofit columns (Excel online), Autofit rows (Excel online), Clear range (Excel online), Get column name (Excel online), Copy Excel range (OneDrive/SharePoint), Copy worksheet (Excel online), Create table (Excel online), Create workbook (Excel online), Delete range (Excel online), Delete table (Excel online), Delete worksheet (Excel online), Find in sheet (Excel online), Find next empty cell (Excel online), Format range (Excel online), Get cell (Excel online), Read cell format (Excel online), Get range values (Excel online), Get table rows (Excel online), Get worksheets (Excel online), Hide worksheet (Excel online), Insert range (Excel online), List tables (Excel online), Read column (Excel online), Read formula (Excel online), Read Excel range (OneDrive/SharePoint), Read row (Excel online), Rename table (Excel online), Rename worksheet (Excel online), Set cell (Excel online), Set formula (Excel online), Set range values (Excel online), Show worksheet (Excel online), Get used range (Excel online), Write Data Table (Excel online), Write Excel range (OneDrive/SharePoint)

## Calendar

### Add event attendees

`ooa.microsoft365/add_attendees` (action)

Invite more people to an existing event; people already invited are not added twice.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Attendees | `attendees` | Any | Yes |  | Main | Address(es) to invite. |
| Type | `type` | Text | No | `required` | Main | required, optional or resource. One of: `required`, `optional`, `resource` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attendees` | List | Attendee addresses after the change. |

*Time limit 60 s; needs: network.*

### Add event attachment

`ooa.microsoft365/add_event_attachment` (action)

Attach a local file of up to 3 MB to a calendar event.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| File | `file` | File path | Yes |  | Main | File to attach. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attachment_id` | Text | Attachment id. |

*Time limit 60 s; needs: network, filesystem.*

### Cancel calendar event

`ooa.microsoft365/cancel_event` (action)

Cancel a meeting you organise and send attendees a cancellation note.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Message | `message` | Text | No | (empty) | Main | Note sent to attendees. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Clear event details

`ooa.microsoft365/clear_event_details` (action)

Blank the ticked details of an event: title, location, description, reminder, repeat pattern or all-day setting.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Clear subject | `clear_subject` | Yes/No | No | No | Main | Empty the title. |
| Clear location | `clear_location` | Yes/No | No | No | Main | Empty the location. |
| Clear body | `clear_body` | Yes/No | No | No | Main | Empty the description. |
| Clear reminder | `clear_reminder` | Yes/No | No | No | Main | Turn the reminder off. |
| Clear recurrence | `clear_recurrence` | Yes/No | No | No | Main | Stop the series repeating; for one occurrence the whole series is changed. |
| Clear all day | `clear_all_day` | Yes/No | No | No | Main | Make an all-day event a timed one, keeping its dates. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `web_link` | Text | Link to open the event in Outlook. |

*Time limit 60 s; safe to repeat; needs: network.*

### Create calendar event

`ooa.microsoft365/create_event` (action)

Create a meeting or appointment, optionally inviting attendees.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Start | `start` | DateTime | Yes |  | Main | Start date and time. |
| End | `end` | DateTime | Yes |  | Main | End date and time. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone of start and end, e.g. India Standard Time or Europe/London. |
| All day | `all_day` | Yes/No | No | No | Main | All-day event: only the dates of start and end count; the end date may equal the start date. |
| Attendees | `attendees` | Any | No |  | Main | Attendee address(es). |
| Optional attendees | `optional_attendees` | Any | No |  | Main | Address(es) invited as optional. |
| Subject | `subject` | Text | Yes |  | Main | Title. |
| Location | `location` | Text | No |  | Main | Location. |
| Body | `body` | Text | No |  | Main | Description. |
| Attachments | `attachments` | List | No |  | Main | Local files (up to 3 MB each) attached to the event after it is created. |
| Reminder (minutes) | `reminder_minutes` | Number | No |  | Main; tick Remind me | Reminder this many minutes before the start; 0 turns the reminder off; Outlook's default when empty. |
| Recurrence | `recurrence` | Text | No | `none` | Main | Repeat the event; weekly repeats on the start's weekday, monthly on its day of the month. One of: `none`, `daily`, `weekly`, `monthly`, `yearly` |
| Recurrence interval | `recurrence_interval` | Number | No | `1` | Main | Repeat every this many days, weeks, months or years. |
| Recurrence end | `recurrence_end` | DateTime | No |  | Main | Last date of the series; it has no end when empty and no count is given. |
| Recurrence count | `recurrence_count` | Number | No |  | Main | Number of occurrences, instead of an end date. |
| Recurrence days of week | `recurrence_days_of_week` | List | No |  | Main; tick Repeat on chosen days | Weekdays the event repeats on, e.g. ["monday", "thursday"]. For weekly repeats, instead of the start's weekday; for monthly or yearly repeats, used with recurrence_week_of_month. Example: `["monday", "thursday"]` |
| Recurrence week of month | `recurrence_week_of_month` | Text | No |  | Main; tick Repeat on chosen days | Monthly or yearly repeats only: repeat on this week of the month (e.g. the second Tuesday) instead of on a fixed day of the month. One of: `first`, `second`, `third`, `fourth`, `last` |
| Online meeting | `online_meeting` | Yes/No | No | No | More options | Add a Teams meeting link. |
| Body is HTML | `html` | Yes/No | No | No | More options | The description is HTML. |
| Importance | `importance` | Text | No |  | More options | Importance; Outlook's default when empty. One of: `low`, `normal`, `high` |
| Show as | `show_as` | Text | No |  | More options | How the time shows in the calendar (oof = out of office); busy when empty. One of: `free`, `tentative`, `busy`, `oof`, `workingElsewhere` |
| Sensitivity | `sensitivity` | Text | No |  | More options | Privacy of the event; normal when empty. One of: `normal`, `personal`, `private`, `confidential` |
| Categories | `categories` | List | No |  | More options | Outlook category names, e.g. ["Finance"]. |
| Calendar | `calendar` | Text | No |  | More options | Calendar name or id to put the event in; the default calendar when empty. Example: `Team holidays` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `event_id` | Text | Event id. |
| `web_link` | Text | Link to open the event in Outlook. |
| `online_meeting_url` | Text | Teams join link, when online_meeting is true. |

*Time limit 60 s; needs: network.*

### Delete calendar event

`ooa.microsoft365/delete_event` (action)

Remove an event from the calendar without sending a cancellation note.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Find free meeting times

`ooa.microsoft365/find_meeting_times` (action)

Suggest time slots in a period when the attendees are free for a meeting of the given length.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Attendees | `attendees` | Any | Yes |  | Main | Attendee address(es). |
| Duration (minutes) | `duration_minutes` | Number | No | `30` | Main | Meeting length in minutes. |
| From | `from` | DateTime | Yes |  | Main | Start of the search period. |
| To | `to` | DateTime | Yes |  | Main | End of the search period. |
| Include outside working (hours) | `include_outside_working_hours` | Yes/No | No | No | More options | Also suggest times outside the attendees' working hours. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone of from, to and the results. |
| Maximum results | `max_results` | Number | No | `10` | More options | Most suggestions. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: start, end, confidence, organizer_availability. |
| `count` | Number | Number of suggestions. |
| `reason` | Text | Why there are no suggestions, when there are none. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get calendar events

`ooa.microsoft365/list_events` (action)

List events between two dates.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| From | `from` | DateTime | Yes |  | Main | Start of the period (UTC). |
| To | `to` | DateTime | Yes |  | Main | End of the period (UTC). |
| Subject contains | `subject_contains` | Text | No |  | Main | Only events whose title contains this text. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone of the returned start and end times, e.g. Europe/London. |
| Calendar ID | `calendar_id` | Text | No |  | More options | Read this calendar instead of the default one. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, subject, start, end, location, organizer. |

*Time limit 60 s; safe to repeat; needs: network.*

### Remove event attendees

`ooa.microsoft365/remove_attendees` (action)

Take people off an existing event's attendee list.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Attendees | `attendees` | Any | Yes |  | Main | Address(es) to remove. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attendees` | List | Attendee addresses after the change. |

*Time limit 60 s; needs: network.*

### Respond to meeting invitation

`ooa.microsoft365/respond_to_event` (action)

Accept, tentatively accept or decline a meeting invitation, with an optional note to the organiser.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Response | `response` | Text | Yes |  | Main | accept, tentative or decline. One of: `accept`, `tentative`, `decline` |
| Comment | `comment` | Text | No | (empty) | Main; tick Add a note | Note to the organiser. |
| Send response | `send_response` | Yes/No | No | Yes | More options | Let the organiser know. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Update calendar event

`ooa.microsoft365/update_event` (action)

Change the title, times, description, location, all-day setting or repeat pattern of an event; empty inputs are left unchanged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Integration event (WID) | `event_id` | Text | Yes |  | Main | Event id from Get calendar events or Create calendar event. |
| Start | `start` | DateTime | No |  | Main | New start date and time. |
| End | `end` | DateTime | No |  | Main | New end date and time. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone of start and end. |
| Subject | `subject` | Text | No |  | Main | New title. |
| Body | `body` | Text | No |  | Main | New description. |
| Location | `location` | Text | No |  | Main | New location. |
| Reminder (minutes) | `reminder_minutes` | Number | No |  | Main; tick Change the reminder | Reminder this many minutes before the start; 0 turns the reminder off; Outlook's default when empty. |
| All day | `all_day` | Yes/No | No |  | Main; tick Change all day | true makes it an all-day event (only the dates count), false a timed one; unchanged when empty. |
| Recurrence | `recurrence` | Text | No |  | Main; tick Change the repeat | New repeat pattern for the whole series; none stops repeating; unchanged when empty. Weekly repeats on the start's weekday, monthly on its day of the month. One of: `none`, `daily`, `weekly`, `monthly`, `yearly` |
| Recurrence interval | `recurrence_interval` | Number | No | `1` | Main; tick Change the repeat | Repeat every this many days, weeks, months or years. |
| Recurrence end | `recurrence_end` | DateTime | No |  | Main; tick Change the repeat | Last date of the series; it has no end when empty and no count is given. |
| Recurrence count | `recurrence_count` | Number | No |  | Main; tick Change the repeat | Number of occurrences, instead of an end date. |
| Body is HTML | `html` | Yes/No | No | No | More options | The description is HTML. |
| Importance | `importance` | Text | No |  | More options | Importance; Outlook's default when empty. One of: `low`, `normal`, `high` |
| Show as | `show_as` | Text | No |  | More options | How the time shows in the calendar (oof = out of office); busy when empty. One of: `free`, `tentative`, `busy`, `oof`, `workingElsewhere` |
| Sensitivity | `sensitivity` | Text | No |  | More options | Privacy of the event; normal when empty. One of: `normal`, `personal`, `private`, `confidential` |
| Categories | `categories` | List | No |  | More options | Outlook category names, e.g. ["Finance"]. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `web_link` | Text | Link to open the event in Outlook. |

*Time limit 60 s; safe to repeat; needs: network.*

## Files

### Check file permission (OneDrive/SharePoint)

`ooa.microsoft365/check_permissions` (action)

Check whether a person has been given direct access to a file or folder and with which roles.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Email | `email` | Text | Yes |  | Main | Email address of the person. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `has_access` | Yes/No | True when the person has a direct permission. |
| `roles` | List | Roles such as read, write or owner. |

*Time limit 60 s; safe to repeat; needs: network.*

### Copy file or folder (OneDrive/SharePoint)

`ooa.microsoft365/copy_item` (action)

Copy a file or folder into another folder, optionally under a new name, and wait for the copy to finish.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder path (empty for the root), or id:{item-id}. |
| If exists | `if_exists` | Text | No | `fail` | Main | When the name is taken at the destination: fail, rename (add a number) or replace. One of: `fail`, `rename`, `replace` |
| New name | `new_name` | Text | No |  | More options | Name for the copy; the original name when empty. |
| To drive | `to_drive` | Text | No |  | More options | Destination drive when different from the source drive. |
| Wait (seconds) | `wait_seconds` | Number | No | `120` | More options | How long to wait for the copy; 0 returns at once. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Id of the copy, once it has finished. |
| `status` | Text | completed, or inProgress when the wait ran out. |
| `monitor_url` | Text | Address to check the copy's progress. |

*Time limit 600 s; needs: network.*

### Create folder (OneDrive/SharePoint)

`ooa.microsoft365/create_folder` (action)

Create a folder inside a drive folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Parent folder | `parent_folder` | Text | No | (empty) | Main | Parent folder path (empty for the root), or id:{item-id}. |
| Name | `name` | Text | Yes |  | Main | New folder name. |
| If exists | `if_exists` | Text | No | `fail` | More options | When the name is taken: fail, rename (add a number) or replace. One of: `fail`, `rename`, `replace` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Folder id. |
| `web_url` | Text | Link to the folder. |

*Time limit 60 s; needs: network.*

### Create sharing link (OneDrive/SharePoint)

`ooa.microsoft365/create_sharing_link` (action)

Create a link that opens a file or folder for viewing or editing, for anyone or only people in the organisation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Type | `type` | Text | No | `view` | Main | view or edit. One of: `view`, `edit` |
| Scope | `scope` | Text | No | `organization` | Main | organization: people in your organisation; anonymous: anyone with the link. One of: `organization`, `anonymous` |
| Expires | `expires` | DateTime | No |  | Main; tick Link expires | When the link stops working (UTC); never when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `link` | Text | The sharing link. |
| `permission_id` | Text | Id of the link's permission. |

*Time limit 60 s; needs: network.*

### Delete file or folder (OneDrive/SharePoint)

`ooa.microsoft365/delete_item` (action)

Delete a file or folder; it goes to the drive's recycle bin unless deleted permanently.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good, skipping the recycle bin (OneDrive for work or school and SharePoint). |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Download file (OneDrive/SharePoint)

`ooa.microsoft365/download_file` (action)

Download a file from a OneDrive or SharePoint path to a local file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Path of the file in the drive. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save it. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing local file; when false a number such as (2) is added to the name. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 600 s; needs: network, filesystem.*

### Export file to PDF (OneDrive/SharePoint)

`ooa.microsoft365/export_to_pdf` (action)

Convert a Word, Excel, PowerPoint or other supported file in the drive to PDF and save it locally.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save the PDF. Example: `out/report.pdf` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing local file; when false a number such as (2) is added to the name. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved PDF path. |

*Time limit 600 s; needs: network, filesystem.*

### Find files (OneDrive/SharePoint)

`ooa.microsoft365/find_items` (action)

Search a drive for files and folders whose name or content matches the text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Text | `text` | Text | Yes |  | Main | Text to look for. |
| Item type | `item_type` | Text | No | `any` | Main | Files, folders or both. One of: `any`, `file`, `folder` |
| In folder | `in_folder` | Text | No |  | Main; tick Search one folder only | Search only inside this folder (path or id:{item-id}); the whole drive when empty. |
| Exact name | `exact_name` | Yes/No | No | No | More options | Keep only items whose name equals the text (not case-sensitive). |
| Maximum results | `max_results` | Number | No | `100` | More options | Maximum items. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, type, size, modified, web_url. |
| `count` | Number | Number of items found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get file information (OneDrive/SharePoint)

`ooa.microsoft365/get_item_info` (action)

Read a file's or folder's id, size, dates, last editor, parent path and link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | id, name, type, size, created, modified, modified_by, parent_path, web_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List files (OneDrive/SharePoint)

`ooa.microsoft365/list_files` (action)

List the files and folders in a drive folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Folder | `folder` | Text | No | (empty) | Main | Folder path; the root when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, type, size, modified, web_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List file permissions (OneDrive/SharePoint)

`ooa.microsoft365/list_permissions` (action)

List who can open or edit a file or folder, including sharing links.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, roles, granted_to, link_type, link_scope, link_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move file or folder (OneDrive/SharePoint)

`ooa.microsoft365/move_item` (action)

Move a file or folder into another folder in the same drive, optionally renaming it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder path (empty for the root), or id:{item-id}. |
| If exists | `if_exists` | Text | No | `fail` | Main | When the name is taken at the destination: fail, rename (add a number) or replace. One of: `fail`, `rename`, `replace` |
| New name | `new_name` | Text | No |  | More options | New name; unchanged when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Item id. |
| `web_url` | Text | Link to the item in its new place. |

*Time limit 60 s; needs: network.*

### Rename file or folder (OneDrive/SharePoint)

`ooa.microsoft365/rename_item` (action)

Give a file or folder a new name in the same folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| New name | `new_name` | Text | Yes |  | Main | New name, including the extension for files. |
| If exists | `if_exists` | Text | No | `fail` | More options | When the new name is taken in the folder: fail, rename (add a number) or replace. One of: `fail`, `rename`, `replace` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Item id. |
| `web_url` | Text | Link to the renamed item. |

*Time limit 60 s; safe to repeat; needs: network.*

### Restore file version (OneDrive/SharePoint)

`ooa.microsoft365/restore_version` (action)

Make an earlier version of a file the current one; by default the version before the current one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Version ID | `version_id` | Text | No | (empty) | Main; choice Version = A specific version | Version to restore, e.g. 3.0; the previous version when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `restored_version` | Text | Id of the version that was restored. |

*Time limit 60 s; needs: network.*

### Share file or folder (OneDrive/SharePoint)

`ooa.microsoft365/share_item` (action)

Give people read or edit access to a file or folder, optionally emailing them an invitation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Item | `item` | Text | Yes |  | Main | Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}. |
| Recipients | `recipients` | Any | Yes |  | Main | Email address(es) of the people to share with. |
| Role | `role` | Text | No | `read` | Main | read to view, write to edit. One of: `read`, `write` |
| Send invitation | `send_invitation` | Yes/No | No | Yes | Main | Email the people a sharing invitation. |
| Message | `message` | Text | No |  | Main; tick Add a note to the invitation | Text added to the invitation. |
| Require sign in | `require_sign_in` | Yes/No | No | Yes | More options | People must sign in to open the item. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `permission_ids` | List | Ids of the new permissions. |

*Time limit 60 s; needs: network.*

### Upload file (OneDrive/SharePoint)

`ooa.microsoft365/upload_file` (action)

Upload a local file (up to 250 MB) to a OneDrive or SharePoint folder path; creates or replaces it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Local path | `local_path` | File path | Yes |  | Main | File to upload. |
| Remote path | `remote_path` | Text | Yes |  | Main | Target path in the drive, including the file name. Example: `Reports/2026/summary.xlsx` |
| If exists | `if_exists` | Text | No | `replace` | Main | When the name is taken at the destination: fail, rename (add a number) or replace. One of: `fail`, `rename`, `replace` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Drive item id. |
| `web_url` | Text | Link to the file. |

*Time limit 600 s; needs: network, filesystem.*

## Teams

### Create one-on-one chat (Teams)

`ooa.microsoft365/create_chat` (action)

Start, or reuse, a one-on-one Teams chat between the signed-in user and another person.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| User | `user` | Text | Yes |  | Main | Other person's id or email address. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `chat_id` | Text | Chat id for Send chat message. |

*Time limit 60 s; needs: network.*

### Get channel by name (Teams)

`ooa.microsoft365/get_channel_by_name` (action)

Find the id of a team's channel from its display name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Team ID | `team_id` | Text | No |  | Main; choice Team = By id | Team id; or give team_name instead. |
| Team name | `team_name` | Text | No |  | Main; choice Team = By name | Team display name (not case-sensitive), among the teams the signed-in user belongs to; used instead of team_id. Example: `Finance` |
| Name | `name` | Text | Yes |  | Main | Channel display name (not case-sensitive), e.g. General. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `channel_id` | Text | Channel id. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get channel details (Teams)

`ooa.microsoft365/get_channel_details` (action)

Read a channel's name, description, membership type, email address, creation date and link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Team ID | `team_id` | Text | Yes |  | Main | Team id. |
| Channel ID | `channel_id` | Text | Yes |  | Main | Channel id. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `channel` | Dictionary | id, name, description, membership_type, email, created, web_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get team by name (Teams)

`ooa.microsoft365/get_team_by_name` (action)

Find the id of a team the user belongs to from its display name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Name | `name` | Text | Yes |  | Main | Team display name (not case-sensitive). |
| User | `user` | Text | No | `me` | More options | me, or a user's id or email address. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `team_id` | Text | Team id. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get team details (Teams)

`ooa.microsoft365/get_team_details` (action)

Read a team's name, description, visibility, archive state and link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Team ID | `team_id` | Text | Yes |  | Main | Team id. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `team` | Dictionary | id, name, description, visibility, archived, web_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get user (Microsoft 365)

`ooa.microsoft365/get_user` (action)

Read a person's directory profile: name, email, job title, department, office and phone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| User | `user` | Text | No | `me` | Main | me, or a user's id or email address. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | id, name, email, user_principal_name, job_title, department, office, mobile_phone. |

*Time limit 60 s; safe to repeat; needs: network.*

### List channels (Teams)

`ooa.microsoft365/list_channels` (action)

List the channels of a team.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Team ID | `team_id` | Text | Yes |  | Main | Team id. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, description, membership_type. |

*Time limit 60 s; safe to repeat; needs: network.*

### List teams (Teams)

`ooa.microsoft365/list_teams` (action)

List the Microsoft Teams teams the user belongs to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| User | `user` | Text | No | `me` | Main | me, or a user's id or email address. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, description. |

*Time limit 60 s; safe to repeat; needs: network.*

### Send chat message (Teams)

`ooa.microsoft365/send_chat_message` (action)

Send a message into an existing Teams chat, or straight to one person, starting a one-on-one chat with them when needed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Chat ID | `chat_id` | Text | No |  | Main; choice Send to = A chat | Chat id, e.g. from Create chat; or give to instead. |
| To | `to` | Text | No |  | Main; choice Send to = A person | Person's email address or user id; their one-on-one chat with the signed-in user is used, or started. Used instead of chat_id. Example: `ann@contoso.com` |
| Message | `message` | Text | Yes |  | Main | Message text (HTML allowed when html is true). |
| Body is HTML | `html` | Yes/No | No | No | More options | Message is HTML. |
| Importance | `importance` | Text | No | `normal` | More options | Importance of the message. One of: `normal`, `high`, `urgent` |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message id. |
| `chat_id` | Text | Chat the message went to. |

*Time limit 60 s; needs: network.*

### Post Teams message

`ooa.microsoft365/teams_message` (action)

Post a message to a Microsoft Teams channel, chosen by id or by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Team ID | `team_id` | Text | No |  | Main; choice Team = By id | Team id; or give team_name instead. |
| Team name | `team_name` | Text | No |  | Main; choice Team = By name | Team display name (not case-sensitive), among the teams the signed-in user belongs to; used instead of team_id. Example: `Finance` |
| Channel ID | `channel_id` | Text | No |  | Main; choice Channel = By id | Channel id; or give channel_name instead. |
| Channel name | `channel_name` | Text | No |  | Main; choice Channel = By name | Channel display name (not case-sensitive); used instead of channel_id. Example: `General` |
| Message | `message` | Text | Yes |  | Main | Message text (HTML allowed when html is true). |
| Body is HTML | `html` | Yes/No | No | No | More options | Message is HTML. |
| Subject | `subject` | Text | No |  | More options | Title shown above the message. |
| Importance | `importance` | Text | No | `normal` | More options | Importance of the message. One of: `normal`, `high`, `urgent` |
| Reply to message ID | `reply_to_message_id` | Text | No |  | More options | Post as a reply in the thread of this message instead of a new post. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message id. |

*Time limit 60 s; needs: network.*

## SharePoint

### Create SharePoint list item

`ooa.microsoft365/create_list_item` (action)

Add an item to a SharePoint list from a dictionary of field names and values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Site | `site` | Text | Yes |  | Main | SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance. |
| List | `list` | Text | Yes |  | Main | List id or display name. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field internal names and values, e.g. {"Title":"Laptop","Status":"Open"}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | New item id. |

*Time limit 60 s; needs: network.*

### Delete SharePoint list item

`ooa.microsoft365/delete_list_item` (action)

Delete an item from a SharePoint list; it goes to the site's recycle bin.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Site | `site` | Text | Yes |  | Main | SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance. |
| List | `list` | Text | Yes |  | Main | List id or display name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Get SharePoint list items

`ooa.microsoft365/get_list_items` (action)

Read the items of a SharePoint list as a Data Table, optionally filtered and limited to some columns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Site | `site` | Text | Yes |  | Main | SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance. |
| List | `list` | Text | Yes |  | Main | List id or display name. |
| Filter | `filter` | Text | No |  | Main; tick Filter the items | OData filter on fields, e.g. fields/Status eq 'Open'. Example: `fields/Status eq 'Open'` |
| Columns | `columns` | List | No |  | Main | Field names to return; all when empty. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Maximum items. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Column id, then one column per list field. |
| `count` | Number | Number of items. |

*Time limit 60 s; safe to repeat; needs: network.*

### List SharePoint lists

`ooa.microsoft365/list_lists` (action)

List the lists and libraries of a SharePoint site.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Site | `site` | Text | Yes |  | Main | SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, display_name, template, web_url. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update SharePoint list item

`ooa.microsoft365/update_list_item` (action)

Change some fields of a SharePoint list item; other fields keep their values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Site | `site` | Text | Yes |  | Main | SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance. |
| List | `list` | Text | Yes |  | Main | List id or display name. |
| Item ID | `item_id` | Text | Yes |  | Main | Item id. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field internal names and their new values. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

## Mail

### Delete all emails (Outlook)

`ooa.microsoft365/delete_all_mail` (action)

Delete every email in a folder that matches the optional filters, to Deleted Items or permanently.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Folder | `folder` | Text | No | `inbox` | Main | Folder id or well-known name: inbox, archive, sentitems, deleteditems. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Permanent | `permanent` | Yes/No | No | No | More options | Remove permanently instead of moving to Deleted Items. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most emails to delete in one run. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of emails deleted. |

*Time limit 600 s; needs: network.*

### Delete email (Outlook)

`ooa.microsoft365/delete_mail` (action)

Delete an email by moving it to Deleted Items, or remove it permanently.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| Permanent | `permanent` | Yes/No | No | No | More options | Remove permanently instead of moving to Deleted Items. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Forward email (Outlook)

`ooa.microsoft365/forward_mail` (action)

Forward an email to new recipients with an optional note above the original message.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| From | `from` | Text | No |  | Main | Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty. Example: `invoices@acme.com` |
| To | `to` | Any | Yes |  | Main | Recipient address(es). |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Attachments | `attachments` | List | No |  | Main | File paths to attach (up to 3 MB each). |
| Comment | `comment` | Text | No | (empty) | Main | Note added above the forwarded message. |
| Importance | `importance` | Text | No | `normal` | More options | Importance shown to the recipients. One of: `low`, `normal`, `high` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Read email (Outlook)

`ooa.microsoft365/get_mail` (action)

Read one email's details and body; optionally mark it read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails. |
| Mark read | `mark_read` | Yes/No | No | Yes | Main | Mark as read. |
| Body as text | `body_as_text` | Yes/No | No | No | Main | Return the body as plain text even when the email is HTML. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `email` | Dictionary | id, from, to, subject, received, body, body_type, has_attachments, cc, bcc, reply_to, sent, importance, flag_status, categories, conversation_id, internet_message_id. |

*Time limit 60 s; needs: network.*

### Find emails (Outlook)

`ooa.microsoft365/list_mail` (action)

List emails in a mail folder, newest first, optionally only unread or matching sender/subject text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Folder | `folder` | Text | No | `inbox` | Main | Folder id or well-known name: inbox, archive, sentitems, deleteditems. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | More options | Only emails that have attachments. |
| Oldest first | `oldest_first` | Yes/No | No | No | More options | Return the oldest emails first instead of the newest. |
| Maximum results | `max_results` | Number | No | `50` | More options | Maximum emails. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, from, subject, received, unread, has_attachments. |
| `count` | Number | Number of emails. |

*Time limit 60 s; safe to repeat; needs: network.*

### Check mail folder exists (Outlook)

`ooa.microsoft365/mail_folder_exists` (action)

Check whether a mail folder exists, by top-level display name or by a path such as Inbox/Invoices, and return its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Name | `name` | Text | Yes |  | Main | Folder display name, e.g. Invoices, or a path such as Inbox/Invoices/2026 (/ or \ between levels). |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exists` | Yes/No | True when the folder exists. |
| `folder_id` | Text | Folder id, empty when it does not exist. |

*Time limit 60 s; safe to repeat; needs: network.*

### Change email status (Outlook)

`ooa.microsoft365/mark_mail` (action)

Mark an email as read or unread, or set, clear or complete its follow-up flag.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| Status | `status` | Text | Yes |  | Main | New status: read, unread, flagged, unflagged or complete. One of: `read`, `unread`, `flagged`, `unflagged`, `complete` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move all emails (Outlook)

`ooa.microsoft365/move_all_mail` (action)

Move every email in a folder that matches the optional filters to another folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder id, well-known name, or a path such as Inbox/Invoices/2026. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Folder | `folder` | Text | No | `inbox` | Main | Source Folder id or well-known name: inbox, archive, sentitems, deleteditems. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most emails to move in one run. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of emails moved. |

*Time limit 600 s; needs: network.*

### Move email (Outlook)

`ooa.microsoft365/move_mail` (action)

Move an email to another folder (id or well-known name such as archive).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder id, well-known name, or a path such as Inbox/Invoices/2026. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `new_id` | Text | The email's id in the destination folder. |

*Time limit 60 s; needs: network.*

### Reply all to email (Outlook)

`ooa.microsoft365/reply_all_mail` (action)

Reply to the sender and every other recipient of an email with a note above the original message.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| From | `from` | Text | No |  | Main | Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty. Example: `invoices@acme.com` |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Exclude sender | `exclude_sender` | Yes/No | No | No | Main | Leave the original sender out and reply to the other recipients. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach (up to 3 MB each). |
| Comment | `comment` | Text | Yes |  | Main | Reply text. |
| Importance | `importance` | Text | No | `normal` | More options | Importance shown to the recipients. One of: `low`, `normal`, `high` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Reply to email (Outlook)

`ooa.microsoft365/reply_mail` (action)

Reply to the sender of an email, or to everyone on it, with a note above the original message.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| Reply all | `reply_all` | Yes/No | No | No | Main | Reply to the sender and every other recipient. |
| From | `from` | Text | No |  | Main | Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty. Example: `invoices@acme.com` |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Attachments | `attachments` | List | No |  | Main | File paths to attach (up to 3 MB each). |
| Comment | `comment` | Text | Yes |  | Main | Reply text. |
| Exclude sender | `exclude_sender` | Yes/No | No | No | More options | Reply all only: leave the original sender out and reply to the other recipients. |
| Importance | `importance` | Text | No | `normal` | More options | Importance shown to the recipients. One of: `low`, `normal`, `high` |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Save attachments of all matching emails (Outlook)

`ooa.microsoft365/save_all_mail_attachments` (action)

Save the attachments of every email in a folder that matches the filters, optionally marking those emails as read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Folder | `folder` | Text | No | `inbox` | Main | Folder id or well-known name: inbox, archive, sentitems, deleteditems. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Mark read | `mark_read` | Yes/No | No | No | More options | Mark each matching email as read afterwards. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most emails handled in one run. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |
| `email_count` | Number | Number of emails that matched. |

*Time limit 600 s; needs: network, filesystem.*

### Save email as EML (Outlook)

`ooa.microsoft365/save_mail_as_eml` (action)

Save a complete email, including attachments, as a standard .eml file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save the .eml file; a path ending in / or an existing folder gets a file named after the subject. Example: `mail/order-123.eml` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing file; when false a number such as (2) is added to the name. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 60 s; needs: network, filesystem.*

### Save email attachments (Outlook)

`ooa.microsoft365/save_mail_attachments` (action)

Save an email's file attachments to a folder, optionally only names matching a pattern such as *.pdf.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| ID | `id` | Text | Yes |  | Main | Email id. |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern. |
| Include inline | `include_inline` | Yes/No | No | Yes | More options | Also save images embedded in the body, such as signature logos. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |

*Time limit 60 s; needs: network, filesystem.*

### Send email (Outlook)

`ooa.microsoft365/send_mail` (action)

Send an email from a Microsoft 365 mailbox with optional CC, BCC and attachments (up to 3 MB each).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| From | `from` | Text | No |  | Main | Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty. Example: `invoices@acme.com` |
| To | `to` | Any | Yes |  | Main | Recipient address(es). |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Subject | `subject` | Text | Yes |  | Main | Subject. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Message body. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Importance | `importance` | Text | No | `normal` | More options | Importance shown to the recipients. One of: `low`, `normal`, `high` |
| Reply to | `reply_to` | Any | No |  | More options | Address(es) that replies go to, when not the sender. |
| Save to sent items | `save_to_sent_items` | Yes/No | No | Yes | More options | Keep a copy in Sent Items. |
| Read receipt | `read_receipt` | Yes/No | No | No | More options | Ask for a receipt when the email is read. |
| Mailbox | `mailbox` | Text | No | `me` | More options | Mailbox: me, or a user's email address (needs application permission). |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 120 s; needs: network.*

## Excel online

### Add table rows (Excel online)

`ooa.microsoft365/excel_add_table_rows` (action)

Append rows to the end of an Excel table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Table | `table` | Text | Yes |  | Main | Table name or id. |
| Rows | `rows` | Any | Yes |  | Main | List of rows (each a list of values), one flat list for a single row, or a Data Table (rows only). |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of rows added. |

*Time limit 60 s; needs: network.*

### Add worksheet (Excel online)

`ooa.microsoft365/excel_add_worksheet` (action)

Add a worksheet to a workbook in OneDrive or SharePoint.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Name | `name` | Text | No |  | Main | Name for the worksheet; Excel picks one when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sheet_name` | Text | Name of the new worksheet. |
| `sheet_id` | Text | Id of the new worksheet. |

*Time limit 60 s; needs: network.*

### Autofit columns (Excel online)

`ooa.microsoft365/excel_autofit_columns` (action)

Resize the columns of a range so their contents fit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range whose columns are resized. Example: `A1:F1` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Autofit rows (Excel online)

`ooa.microsoft365/excel_autofit_rows` (action)

Resize the rows of a range so their contents fit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range whose rows are resized. Example: `A1:A50` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Clear range (Excel online)

`ooa.microsoft365/excel_clear_range` (action)

Clear the values, the formatting, or both from a range without shifting other cells.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A2:F100` |
| Clear | `clear` | Text | No | `all` | Main | What to clear: all, contents or formats. One of: `all`, `contents`, `formats` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get column name (Excel online)

`ooa.microsoft365/excel_column_name` (action)

Split a cell address such as AB7 into its column letters, column number and row number; nothing is read from the workbook.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | Yes |  | Main | Cell address; a sheet name before ! and $ signs are ignored. Example: `AB7` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `column_name` | Text | Column letters, e.g. AB. |
| `column` | Number | Column number, starting at 1 for A. |
| `row` | Number | Row number. |

*Time limit 60 s; safe to repeat.*

### Copy Excel range (OneDrive/SharePoint)

`ooa.microsoft365/excel_copy_range` (action)

Copy a cell or range to another place in the same workbook, as values or with formulas, and optionally with number formats.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet the range is on. |
| Range | `range` | Text | Yes |  | Main | Cell or range to copy. Example: `A2:D10` |
| Destination | `destination` | Text | Yes |  | Main | Top-left cell to copy to. Example: `H2` |
| Destination sheet | `destination_sheet` | Text | No |  | Main; tick To another sheet | Worksheet to copy to; the same sheet when empty. |
| Copy | `copy` | Text | No | `values` | More options | values copies the results; formulas copies formulas exactly as written (cell references are not moved). One of: `values`, `formulas` |
| Include number formats | `include_number_formats` | Yes/No | No | Yes | More options | Also copy number formats such as dates and currency. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | The range that was written, e.g. H2:K10. |

*Time limit 60 s; needs: network.*

### Copy worksheet (Excel online)

`ooa.microsoft365/excel_copy_worksheet` (action)

Copy a worksheet's cells (values, formulas and number formats) into a new worksheet, in the same workbook or another one. Colours, fonts and column widths are not copied.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet to copy. |
| Target remote path | `target_remote_path` | Text | No |  | Main; tick Into another workbook | Workbook to copy into (path in the same drive, or id:{item-id}); the same workbook when empty. |
| New name | `new_name` | Text | No |  | Main | Name of the new worksheet; the source sheet's name when empty, which must then be free in the target workbook. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sheet_name` | Text | Name of the new worksheet. |
| `address` | Text | Cells written on the new worksheet, e.g. A1:F42; empty when the source sheet is empty. |

*Time limit 60 s; needs: network.*

### Create table (Excel online)

`ooa.microsoft365/excel_create_table` (action)

Turn a range into an Excel table, optionally naming it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Name | `name` | Text | No |  | Main | Table name; Excel picks one when empty. |
| Range | `range` | Text | Yes |  | Main | Range the table covers. Example: `A1:D1` |
| Has headers | `has_headers` | Yes/No | No | Yes | Main | The first row holds column names. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table_id` | Text | Table id. |
| `table_name` | Text | Table name. |

*Time limit 60 s; needs: network.*

### Create workbook (Excel online)

`ooa.microsoft365/excel_create_workbook` (action)

Create a new, empty Excel workbook in OneDrive or SharePoint, optionally with named sheets.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Path of the new workbook, ending in .xlsx. Example: `Reports/2026/new.xlsx` |
| Sheet names | `sheet_names` | List | No |  | More options | Names of the sheets; one sheet called Sheet1 when empty. |
| If exists | `if_exists` | Text | No | `fail` | More options | When a file of that name exists: fail, rename (add a number) or replace it. One of: `fail`, `rename`, `replace` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `item_id` | Text | Drive item id of the workbook. |
| `web_url` | Text | Link to open the workbook. |

*Time limit 60 s; needs: network.*

### Delete range (Excel online)

`ooa.microsoft365/excel_delete_range` (action)

Delete the cells of a range and close the gap by moving cells up or left, or delete the whole rows or columns the range covers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A5:F5` |
| Shift | `shift` | Text | No | `up` | Main | Move the remaining cells up or left; entire_row or entire_column deletes the whole rows or columns of the range. One of: `up`, `left`, `entire_row`, `entire_column` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Delete table (Excel online)

`ooa.microsoft365/excel_delete_table` (action)

Delete an Excel table together with its data.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Table | `table` | Text | Yes |  | Main | Table name or id. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Delete worksheet (Excel online)

`ooa.microsoft365/excel_delete_worksheet` (action)

Delete a worksheet and everything on it from a workbook in OneDrive or SharePoint.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Worksheet name; or give sheet_position instead. |
| Sheet position | `sheet_position` | Number | No |  | Main; choice Sheet by = Position | Position of the worksheet among the tabs, the first being 1; used instead of sheet. Example: `2` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; needs: network.*

### Find in sheet (Excel online)

`ooa.microsoft365/excel_find` (action)

Search the used cells of a sheet, or a range, for text and return the addresses of the matching cells.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | No |  | Main; tick Search only a range | Range to search; the used cells when empty. Example: `A1:F200` |
| Text | `text` | Text | Yes |  | Main | Text to look for in the displayed cell text. |
| Search by | `search_by` | Text | No | `rows` | Main | Order of the results: rows goes across each row first, columns goes down each column first. One of: `rows`, `columns` |
| Match case | `match_case` | Yes/No | No | No | Main | Upper and lower case must match. |
| Whole cell | `whole_cell` | Yes/No | No | No | Main | The whole cell must equal the text. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cells` | List | Addresses of the matches, e.g. B7, in search order. |
| `count` | Number | Number of matches. |

*Time limit 60 s; safe to repeat; needs: network.*

### Find next empty cell (Excel online)

`ooa.microsoft365/excel_find_next_empty_cell` (action)

Find the first empty cell after the last value in a column (going down) or in a row (going right), e.g. where to append the next record.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Direction | `direction` | Text | No | `down` | Main | down: after the last value of a column; right: after the last value of a row. One of: `down`, `right` |
| Column | `column` | Text | No | `A` | Main | Column letter to look in. Shown when: `{"direction": ["down"]}` |
| Row | `row` | Number | No | `1` | Main | Row number to look in. Range: 1 to ; Shown when: `{"direction": ["right"]}` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cell` | Text | Address of the empty cell, e.g. A43. |
| `row` | Number | Its row number. |
| `column` | Text | Its column letter. |

*Time limit 60 s; safe to repeat; needs: network.*

### Format range (Excel online)

`ooa.microsoft365/excel_format_range` (action)

Set fill, font, alignment, wrapping, size, borders, merging and number format of a range; empty settings are left as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A1:F1` |
| Fill color | `fill_color` | Text | No |  | Main | Background colour as #RRGGBB or a colour name. Example: `#FFF2CC` |
| Font color | `font_color` | Text | No |  | Main | Text colour as #RRGGBB or a colour name. Example: `#C00000` |
| Bold | `bold` | Yes/No | No |  | Main | Make the text bold (true) or normal (false). |
| Number format | `number_format` | Text | No |  | Main | Excel number format code for every cell. Example: `#,##0.00` |
| Italic | `italic` | Yes/No | No |  | More options | Italic on or off; unchanged when empty. |
| Underline | `underline` | Yes/No | No |  | More options | Single underline on or off; unchanged when empty. |
| Strikethrough | `strikethrough` | Yes/No | No |  | More options | Strikethrough on or off; unchanged when empty. |
| Font name | `font_name` | Text | No |  | More options | Font name; unchanged when empty. Example: `Calibri` |
| Font size | `font_size` | Number | No |  | More options | Font size in points; unchanged when empty. |
| Horizontal alignment | `horizontal_alignment` | Text | No |  | More options | Horizontal alignment; unchanged when empty. One of: `General`, `Left`, `Center`, `Right`, `Fill`, `Justify`, `CenterAcrossSelection`, `Distributed` |
| Vertical alignment | `vertical_alignment` | Text | No |  | More options | Vertical alignment; unchanged when empty. One of: `Top`, `Center`, `Bottom`, `Justify`, `Distributed` |
| Wrap text | `wrap_text` | Yes/No | No |  | More options | Wrap text in the cells on or off; unchanged when empty. |
| Column width | `column_width` | Number | No |  | More options | Column width in points; unchanged when empty. |
| Row height | `row_height` | Number | No |  | More options | Row height in points; unchanged when empty. |
| Border style | `border_style` | Text | No |  | More options | Line style of every border of the range, inside and out; unchanged when empty. One of: `None`, `Continuous`, `Dash`, `DashDot`, `DashDotDot`, `Dot`, `Double`, `SlantDashDot` |
| Border color | `border_color` | Text | No |  | More options | Colour of those borders. Example: `#000000` |
| Merge | `merge` | Yes/No | No |  | More options | Merge the cells (true) or unmerge them (false); unchanged when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get cell (Excel online)

`ooa.microsoft365/excel_get_cell` (action)

Read one cell's value and the text Excel shows for it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `C7` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Cell value (number, text or boolean). |
| `text` | Text | Displayed text with number formatting applied. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read cell format (Excel online)

`ooa.microsoft365/excel_get_cell_format` (action)

Read one cell's fill colour, font (name, size, colour, bold, italic, underline), alignment, wrapping and number format.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `C7` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `format` | Dictionary | fill_color, font_name, font_size, font_color, bold, italic, underline, horizontal_alignment, vertical_alignment, wrap_text, number_format. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get range values (Excel online)

`ooa.microsoft365/excel_get_range` (action)

Read the cell values of a range as a list of rows, without treating the first row as headers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A1:D20` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | List of rows, each a list of cell values. |
| `row_count` | Number | Number of rows. |
| `column_count` | Number | Number of columns. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get table rows (Excel online)

`ooa.microsoft365/excel_get_table_rows` (action)

Read all rows of an Excel table, or one row by its position, as a Data Table, using the table's header names as columns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Table | `table` | Text | Yes |  | Main | Table name or id. |
| Row index | `row_index` | Number | No |  | Main; tick Only one row | Return only this data row; 1 is the first row under the headers. All rows when empty. Range: 1 to  |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | The table's rows. |
| `count` | Number | Number of rows. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get worksheets (Excel online)

`ooa.microsoft365/excel_get_worksheets` (action)

List the worksheets of a workbook in OneDrive or SharePoint, in tab order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sheets` | List | Worksheet names. |
| `table` | Table | Columns: id, name, position, visibility. |

*Time limit 60 s; safe to repeat; needs: network.*

### Hide worksheet (Excel online)

`ooa.microsoft365/excel_hide_worksheet` (action)

Hide a worksheet tab without deleting it; very hidden sheets can only be shown again by an agent or code.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Very hidden | `very_hidden` | Yes/No | No | No | More options | Hide it so that it cannot be shown from Excel's menu. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Insert range (Excel online)

`ooa.microsoft365/excel_insert_range` (action)

Insert empty cells at a range, pushing the existing cells down or right, or insert whole rows or columns where the range is.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A5:F5` |
| Shift | `shift` | Text | No | `down` | Main | Push the existing cells down or right; entire_row or entire_column inserts as many whole rows or columns as the range covers. One of: `down`, `right`, `entire_row`, `entire_column` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | Address of the inserted cells. |

*Time limit 60 s; needs: network.*

### List tables (Excel online)

`ooa.microsoft365/excel_list_tables` (action)

List the Excel tables in a workbook, or on one worksheet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | No | (empty) | Main; tick Only one worksheet | Only tables on this worksheet; all when empty. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, style, show_headers. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read column (Excel online)

`ooa.microsoft365/excel_read_column` (action)

Read one sheet column from row 1 to the last used row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Column | `column` | Text | Yes |  | Main | Column letter, or its number starting at 1. Example: `C` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values; the first item is row 1. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read formula (Excel online)

`ooa.microsoft365/excel_read_formula` (action)

Read the formula in a cell together with its current value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `D10` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `formula` | Text | Formula, or the plain value when the cell has none. |
| `value` | Any | Current value of the cell. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read Excel range (OneDrive/SharePoint)

`ooa.microsoft365/excel_read_range` (action)

Read a range, or all cells in use, from an Excel workbook stored in OneDrive or SharePoint, without downloading it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive. Example: `Finance/budget.xlsx` |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. Example: `Sheet1` |
| Range | `range` | Text | No |  | Main; choice Cells = A range | Range address; all cells in use on the sheet when empty. Example: `A1:D20` |
| First row is the header | `header` | Yes/No | No | Yes | Main | The first row holds the column names. |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Range values; the first row gives the column names when header is on, else Column1, Column2 ... |

*Time limit 60 s; safe to repeat; needs: network.*

### Read row (Excel online)

`ooa.microsoft365/excel_read_row` (action)

Read one sheet row from column A to the last used column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Row | `row` | Number | Yes |  | Main | Row number, starting at 1. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values; the first item is column A. |

*Time limit 60 s; safe to repeat; needs: network.*

### Rename table (Excel online)

`ooa.microsoft365/excel_rename_table` (action)

Give an Excel table a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Table | `table` | Text | Yes |  | Main | Table name or id. |
| New name | `new_name` | Text | Yes |  | Main | New table name (no spaces). |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Rename worksheet (Excel online)

`ooa.microsoft365/excel_rename_worksheet` (action)

Give a worksheet in a OneDrive or SharePoint workbook a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Current worksheet name; or give sheet_position instead. |
| Sheet position | `sheet_position` | Number | No |  | Main; choice Sheet by = Position | Position of the worksheet among the tabs, the first being 1; used instead of sheet. Example: `2` |
| New name | `new_name` | Text | Yes |  | Main | New worksheet name. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set cell (Excel online)

`ooa.microsoft365/excel_set_cell` (action)

Write one value into a cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `C7` |
| Value | `value` | Any | Yes |  | Main | Value to write; empty clears the value. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set formula (Excel online)

`ooa.microsoft365/excel_set_formula` (action)

Put a formula into a cell and return the value Excel calculates.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `D10` |
| Formula | `formula` | Text | Yes |  | Main | Formula; the leading = is added when missing. Example: `=SUM(D2:D9)` |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Calculated value of the formula. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set range values (Excel online)

`ooa.microsoft365/excel_set_range` (action)

Write a list of rows into a sheet; a single cell address is grown to fit the values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Range | `range` | Text | Yes |  | Main | Top-left cell, or a full range matching the values' size. Example: `B2` |
| Values | `values` | Any | Yes |  | Main | List of rows (each a list of values), one flat list for a single row, or a Data Table (rows only). |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | The range that was written. |

*Time limit 60 s; safe to repeat; needs: network.*

### Show worksheet (Excel online)

`ooa.microsoft365/excel_show_worksheet` (action)

Make a hidden worksheet tab visible again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get used range (Excel online)

`ooa.microsoft365/excel_used_range` (action)

Find the block of cells on a sheet that holds values, with its last row and column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | Used range, e.g. A1:F42; empty for an empty sheet. |
| `row_count` | Number | Rows in the used range. |
| `column_count` | Number | Columns in the used range. |
| `last_row` | Number | Sheet row number of the last used row (0 when empty). |
| `last_column` | Text | Letter of the last used column. |

*Time limit 60 s; safe to repeat; needs: network.*

### Write Data Table (Excel online)

`ooa.microsoft365/excel_write_data_table` (action)

Write a Data Table to a sheet starting at a cell, with or without its column names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}. |
| Table | `table` | Table | Yes |  | Main | Data to write. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Start cell | `start_cell` | Text | No | `A1` | Main | Top-left cell. |
| Include headers | `include_headers` | Yes/No | No | Yes | More options | Write the column names as the first row. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | The range that was written. |

*Time limit 60 s; safe to repeat; needs: network.*

### Write Excel range (OneDrive/SharePoint)

`ooa.microsoft365/excel_write_range` (action)

Write a table into an Excel workbook in OneDrive or SharePoint, starting at a cell (column names first).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | Microsoft Graph access token from the vault. |
| Remote path | `remote_path` | Text | Yes |  | Main | Workbook path in the drive. |
| Sheet | `sheet` | Text | Yes |  | Main | Worksheet name. |
| Start cell | `start_cell` | Text | No | `A1` | Main | Top-left cell. |
| Table | `table` | Table | Yes |  | Main | Data to write. |
| Drive | `drive` | Text | No | `me/drive` | More options | me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Endpoint address | `base_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Graph endpoint; change only for national clouds or testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `address` | Text | The range that was written. |

*Time limit 60 s; needs: network.*
