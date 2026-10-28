# Application

Toolkit `ooa.application`, version 1.0.0. Open files, start, run and stop programs on the runner.

[All toolkits](README.md)

## Contents

- **Application**: Open file, Run program and wait, Start program, Stop program
- **Conditions**: Program is running

## Application

### Open file

`ooa.application/open_file` (action)

Open a document with its default application, e.g. a PDF in the PDF viewer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to open. |

*Time limit 60 s; needs: desktop.*

### Run program and wait

`ooa.application/run_program` (action)

Run a program, wait for it to finish and return its exit code and output.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Program | `program` | Text | Yes |  | Main | Program name or full path. |
| Arguments | `arguments` | List | No |  | Main | Arguments, one list item each (no shell quoting needed). |
| Working folder | `working_folder` | File path | No |  | Main | Folder to run in; the workspace when empty. |
| Input | `input` | Text | No |  | More options | Text sent to the program's standard input. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 0 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the exit code is not 0. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the program. |
| Output encoding | `output_encoding` | Text | No | `utf-8` | More options | Text encoding of the program's input and output, e.g. utf-8, cp850, windows-1252. Example: `cp850` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exit_code` | Number | Exit code; 0 usually means success. |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |

*Time limit 3600 s.*

### Start program

`ooa.application/start_program` (action)

Start a program and continue without waiting; returns its process id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Program | `program` | Text | Yes |  | Main | Program name or full path. Example: `notepad.exe` |
| Working folder | `working_folder` | File path | No |  | Main | Folder to start in. |
| Arguments | `arguments` | List | No |  | Main | Arguments, one list item each. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | Pause this long after starting so the program can open its window; 0 continues at once. Range: 0 to  |
| Window state | `window_state` | Text | No | `normal` | More options | How the program's main window opens (Windows); other systems always use normal. One of: `normal`, `minimized`, `maximized`, `hidden` |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the program; an empty value removes one. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `process_id` | Number | Process id, used by Stop program. |

*Time limit 60 s; needs: desktop.*

### Stop program

`ooa.application/stop_program` (action)

Stop a program by process id or by program name (e.g. EXCEL.EXE).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process ID | `process_id` | Number | No |  | Main; choice Program to stop = By process id | Process id from Start program. Shown when: `{"name": [""]}` |
| Name | `name` | Text | No |  | Main; choice Program to stop = By name | Program file name, e.g. notepad.exe. Shown when: `{"process_id": [""]}` |
| Force | `force` | Yes/No | No | No | Main | Force-stop if it does not close normally. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `stopped` | Number | Number of processes stopped. |

*Time limit 60 s.*

## Conditions

Used in If, Loop while and Wait until steps.

### Program is running

`ooa.application/is_running` (condition)

True when a process with this program file name is running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Program file name, e.g. EXCEL.EXE. |
