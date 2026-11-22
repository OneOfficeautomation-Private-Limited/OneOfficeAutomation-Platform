# Trigger Loop

Toolkit `ooa.triggerloop`, version 1.0.0. Wait for events (file and folder changes, timer ticks, programs starting or stopping, windows opening or closing, services changing state, form events, new e-mails or records, key presses, clicks) and run the loop's nested steps once per event.

[All toolkits](README.md)

## Contents

- **Conditions**: Program is running
- **Loop types**: Each new or changed file in folder, Each event on an open form, Each time a key is pressed, Each click in a window, Each new e-mail, record or item, Each time a program starts or stops, Each time a service changes state, Every interval, Each time a window opens or closes

## Conditions

Used in If, Loop while and Wait until steps.

### Program is running

`ooa.triggerloop/process_running` (condition)

True when at least one process with this executable name is running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process name | `process_name` | Text | Yes |  | Main | Executable name, with or without .exe. |

## Loop types

Used as the kind of loop in a Loop step.

### Each new or changed file in folder

`ooa.triggerloop/file_arrives` (loop type)

Wait for files (or folders) to appear, change, go or be renamed in a folder and run the nested steps once for each; gives the change, path, name, size and time (UTC, like every DateTime).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | File path | Yes |  | Main | Folder to watch. |
| Pattern | `pattern` | Text | No | `*` | Main | Glob for file names, e.g. *.pdf. |
| Include existing | `include_existing` | Yes/No | No | No | Main | Also handle files already in the folder when the loop starts. |
| Settle (seconds) | `settle_seconds` | Number | No | `1` | Main | Wait until a file has not changed for this long, so half-copied files are skipped until complete. |
| Poll (seconds) | `poll_seconds` | Number | No | `1` | Main | How often to check, in seconds. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |
| Event | `event` | Text | No | `created` | Main | Which change starts the steps: a file that appears (created), changes, goes, is renamed, or any of these. One of: `created`, `modified`, `deleted`, `renamed`, `any` |
| Watch | `watch` | Text | No | `files` | Main | Watch files, folders, or both. One of: `files`, `folders`, `both` |
| Include subfolders | `include_subfolders` | Yes/No | No | No | Main | Also watch everything in the folders under it. |

*Needs: filesystem.*

### Each event on an open form

`ooa.triggerloop/form_events` (loop type)

Run the nested steps each time the person presses a button, changes a field or submits the open form (Display form with Wait off); the loop ends when the form closes.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form, as given when it was displayed. |
| Events | `events` | Text | No | `any` | Main | Which events start the steps. One of: `any`, `button`, `change`, `submit` |
| Element | `element` | Text | No |  | Main | Only events of this field or button (its id in the form designer); any when empty. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Each time a key is pressed

`ooa.triggerloop/key_pressed` (loop type)

Windows only. Run the nested steps each time the named key or combination (such as F9, CTRL+ENTER or SHIFT+F2) is pressed, optionally only while a given window is in front. Typed text is never recorded: the event gives only the key, the window title and the process. The key still reaches the application.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Key | `key` | Text | Yes |  | Main | The key or combination: CTRL, ALT, SHIFT, WIN with one key such as A-Z, 0-9, F1-F24, ENTER, TAB, ESC, arrows. Letters and digits need CTRL, ALT or WIN. Example: `CTRL+ENTER` |
| Window | `window` | Text | No |  | Main | Only while this window is in front: title text, a pattern with * and ?, or re: and a regular expression. Empty: any window. |
| Process | `process` | Text | No |  | Main | Only in this program, such as excel.exe. Empty: any program. |
| Debounce (milliseconds) | `debounce_ms` | Number | No | `500` | Main | Ignore repeats within this many milliseconds of the last event. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Each click in a window

`ooa.triggerloop/mouse_clicked` (loop type)

Windows only. Run the nested steps each time the person clicks in a given window, optionally only on a given element (name, type, automation id as captured). The event gives the button, the window title, the process and the element's name, type and automation id; never screen contents. Clicks made by programs are ignored.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main | The window: title text, a pattern with * and ?, or re: and a regular expression. Give this or the process. |
| Process | `process` | Text | No |  | Main | The program, such as excel.exe. Give this or the window. |
| Button | `button` | Text | No | `left` | Main | left, right, middle or any. |
| Element name | `element_name` | Text | No |  | Main | Only clicks on an element with this name (text, * and ? pattern, or re:). Empty: any element. |
| Element type | `element_type` | Text | No |  | Main | Only clicks on this type of element, such as Button or MenuItem. Empty: any type. Example: `Button` |
| Automation ID | `automation_id` | Text | No |  | Main | Only clicks on the element with this automation id. Empty: any. |
| Debounce (milliseconds) | `debounce_ms` | Number | No | `500` | Main | Ignore repeats within this many milliseconds of the last event. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Each new e-mail, record or item

`ooa.triggerloop/new_items` (loop type)

Read a loop type (such as Each email in a mailbox, Outlook, Exchange or Gmail) or a command's list (such as ServiceNow Query records) again and again, and run the nested steps for each item not seen before, or changed since.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | Text | Yes |  | Main | The loop type (toolkit/loop type) or command (toolkit/command) whose items are watched. Example: `ooa.email/emails` |
| Source inputs | `source_inputs` | Dictionary | No |  | Main | The source's fields, such as the folder, the sender or subject filter, or the query; keep secrets in a saved connection. |
| Connection | `connection` | Text | No |  | Main | Saved connection that gives the source its address and sign-in. |
| List output | `list_output` | Text | No |  | Main | For a command: the output that holds the list; its first list output when empty. |
| Key | `key` | Text | No |  | Main | Field that tells items apart, such as id, message_id or sys_id; the whole item when empty. Example: `id` |
| Changed field | `changed_field` | Text | No |  | Main | Also run the steps for an item seen before when this field has changed (an updated record). Example: `sys_updated_on` |
| Include existing | `include_existing` | Yes/No | No | No | Main | Also handle the items there when the loop starts. |
| Poll (seconds) | `poll_seconds` | Number | No | `120` | Main | How often to read the source, in seconds. Range: 1 to  |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Each time a program starts or stops

`ooa.triggerloop/process_starts` (loop type)

Watch for a program (by executable name, e.g. excel or notepad.exe) to start, or to stop, and run the nested steps for each process.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process name | `process_name` | Text | Yes |  | Main | Executable name, with or without .exe; not case-sensitive. Example: `notepad.exe` |
| Poll (seconds) | `poll_seconds` | Number | No | `1` | Main | How often to check, in seconds. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |
| Event | `event` | Text | No | `starts` | Main | Start the steps when the program starts, or when one of its processes ends. One of: `starts`, `stops` |

### Each time a service changes state

`ooa.triggerloop/service_changes` (loop type)

Windows only. Watch a service and run the nested steps when it starts, stops, pauses or resumes; gives the change and the new state.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Service | `service` | Text | Yes |  | Main | Service name (not its display name), e.g. Spooler. Example: `Spooler` |
| Event | `event` | Text | No | `any` | Main | Which change starts the steps. One of: `starts`, `stops`, `pauses`, `resumes`, `any` |
| Include current | `include_current` | Yes/No | No | No | Main | Also run the steps once at the start for the state the service is in (is running, has stopped, is paused). |
| Poll (seconds) | `poll_seconds` | Number | No | `5` | Main | How often to check, in seconds. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Every interval

`ooa.triggerloop/timer_tick` (loop type)

Run the nested steps on a fixed interval, e.g. every 60 seconds; gives the tick number and time (UTC).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Interval (seconds) | `interval_seconds` | Number | Yes |  | Main | Seconds between ticks. |
| Run immediately | `run_immediately` | Yes/No | No | No | Main | Fire the first tick at once instead of after one interval. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |

### Each time a window opens or closes

`ooa.triggerloop/window_changes` (loop type)

Watch for a window whose title fits a pattern to open, or to close, and run the nested steps for each; gives the change and the window's title.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | Yes |  | Main | Window title: text the title contains, a pattern with * and ?, or re: and a regular expression. Example: `*Invoice*` |
| Event | `event` | Text | No | `opens` | Main | Start the steps when such a window opens, closes, or either. One of: `opens`, `closes`, `any` |
| Include existing | `include_existing` | Yes/No | No | No | Main | Also count windows already open when the loop starts as opening. |
| Poll (seconds) | `poll_seconds` | Number | No | `1` | Main | How often to check, in seconds. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |
