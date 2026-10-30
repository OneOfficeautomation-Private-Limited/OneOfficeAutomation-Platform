# Trigger Loop

Toolkit `ooa.triggerloop`, version 1.0.0. Wait for events (new files, timer ticks, process starts) and run the loop's nested steps once per event.

[All toolkits](README.md)

## Contents

- **Conditions**: Program is running
- **Loop types**: Each new file in folder, Each time a program starts, Every interval

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

Wait for files to appear in a folder and run the nested steps once for each, oldest first; gives path, name, size and time.

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

Run the nested steps on a fixed interval, e.g. every 60 seconds; gives the tick number and time.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Interval (seconds) | `interval_seconds` | Number | Yes |  | Main | Seconds between ticks. |
| Run immediately | `run_immediately` | Yes/No | No | No | Main | Fire the first tick at once instead of after one interval. |
| Maximum events | `max_events` | Number | No | `0` | Main | Stop after this many events; 0 = no limit. |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | Main | Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs). |
