# Trigger Loop

Toolkit `ooa.triggerloop`, version 1.0.0. Wait for events (new files, timer ticks, process starts, key presses, clicks) and run the loop's nested steps once per event.

[All toolkits](README.md)

## Contents

- **Conditions**: Program is running
- **Loop types**: Each new file in folder, Each time a key is pressed, Each click in a window, Each time a program starts, Every interval

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

### Each new file in folder

`ooa.triggerloop/file_arrives` (loop type)

Wait for files to appear in a folder and run the nested steps once for each, oldest first; gives path, name, size and time (UTC, like every DateTime).

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

*Needs: filesystem.*

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

### Each time a program starts

`ooa.triggerloop/process_starts` (loop type)

Watch for a program to start (by executable name, e.g. excel or notepad.exe) and run the nested steps for each new process.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process name | `process_name` | Text | Yes |  | Main | Executable name, with or without .exe; not case-sensitive. Example: `notepad.exe` |
| Poll (seconds) | `poll_seconds` | Number | No | `1` | Main | How often to check, in seconds. |
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
