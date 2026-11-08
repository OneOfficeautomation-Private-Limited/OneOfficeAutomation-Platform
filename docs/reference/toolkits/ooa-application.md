# Application

Toolkit `ooa.application`, version 1.0.0. Open files, start, run and stop programs on the runner.

[All toolkits](README.md)

## Contents

- **Application**: Close command session, Open file, Open program/file, Open command session, Read command session, Run program and wait, Start program, Stop program, Write to command session
- **Conditions**: Program is running

## Application

### Close command session

`ooa.application/close_session` (action)

End a command session: optionally ask the program to exit, then stop it and every program it started.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `cmd` | Main | Name of the command session, from Open command session. |
| Send exit | `send_exit` | Yes/No | No | Yes | Main | Send the line exit first, so the program can end by itself. |
| Wait (seconds) | `wait_seconds` | Number | No | `5` | More options | How long to wait for it to end by itself before it is stopped. Range: 0 to 60 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exit_code` | Number | The program's exit code (1 when it had to be stopped). |

*Time limit 60 s.*

### Open file

`ooa.application/open_file` (action)

Open a document with its default application, e.g. a PDF in the PDF viewer. Kept for older agents: new ones use Open program/file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to open. |

*Time limit 60 s; needs: desktop, filesystem.*

### Open program/file

`ooa.application/open_program_file` (action)

Open a program with its parameters, or a file in the application that opens it (a PDF in the PDF viewer).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Location | `location` | File path | Yes |  | Main | The program or file to open: browse for it or type its path. A $variable works too. Example: `C:/Program Files/Notepad++/notepad++.exe` |
| Parameters | `parameters` | Text | No |  | Main | For a program: what to pass to it, as typed after it on a command line. Words in quotes stay together. |
| Start in | `start_in` | File path | No |  | Main | For a program: the folder it starts in. Empty: the agent's working folder. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | Pause this long after opening so its window can appear; 0 continues at once. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `process_id` | Number | The program's process id, for Stop program; 0 when a file was opened in its application. |

*Time limit 60 s; needs: desktop, filesystem.*

### Open command session

`ooa.application/open_session` (action)

Start a console program such as cmd.exe or powershell.exe and keep it open, so later steps can write to it and read its output.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `cmd` | Main | Name for this session; later steps use it. |
| Program | `program` | Text | No | `cmd.exe` | Main | Console program to run: cmd.exe, powershell.exe or any program that reads its standard input. Example: `powershell.exe` |
| Arguments | `arguments` | List | No |  | Main | Arguments, one list item each. For powershell.exe or pwsh with none, it reads commands from this session (-NoLogo -NoProfile -Command -). |
| Working folder | `working_folder` | File path | No |  | Main | Folder to start in; the workspace when empty. |
| Output encoding | `output_encoding` | Text | No | `console` | More options | Text encoding of what is written and read: console (the code page console programs use here), utf-8 (any language; the program runs in a UTF-8 console, but cmd.exe itself can then only take ASCII typed text), or a name such as cp850. Example: `utf-8` |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the program; an empty value removes one. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `process_id` | Number | Process id of the program. |

*Time limit 120 s; needs: filesystem.*

### Read command session

`ooa.application/read_session` (action)

Read what the program of a command session printed, optionally waiting until some text (e.g. its prompt) appears.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `cmd` | Main | Name of the command session, from Open command session. |
| Wait for | `wait_for` | Text | No |  | Main | Wait until this appears in the output, e.g. the prompt or a question. Empty: return what is there once the output has been quiet for half a second. Example: `>` |
| Match type | `match_type` | Text | No | `text` | Main | How wait_for is matched: as plain text, or as a regular expression, e.g. [A-Z]:\\.*>$ for any cmd prompt. One of: `text`, `regex` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Longest time to wait. Range: 0 to 3500 |
| From | `from` | Text | No | `new` | More options | new: only output since the last read; all: everything the session has printed (the newest 2,000,000 characters). One of: `new`, `all` |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | More options | When wait_for does not appear in time (or the program ends first), fail the step; off: return with matched false. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | The output read (up to the end of wait_for when it was found); secrets written to the session show as ****. |
| `matched` | Yes/No | True when wait_for was found. |
| `exited` | Yes/No | True when the program has ended. |
| `exit_code` | Number | The program's exit code once it has ended; -1 while it runs. |

*Time limit 3600 s.*

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
| Allow shell operators | `allow_shell_operators` | Yes/No | No | No | More options | For cmd.exe and .bat/.cmd files only: let arguments contain & \| < > ^ or quotes, which cmd reads as command operators. Leave off when arguments carry data (file names, e-mail subjects): such characters are then refused, so data can never run a command. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exit_code` | Number | Exit code; 0 usually means success. |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |

*Time limit 3600 s; needs: filesystem.*

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
| Allow shell operators | `allow_shell_operators` | Yes/No | No | No | More options | For cmd.exe and .bat/.cmd files only: let arguments contain & \| < > ^ or quotes, which cmd reads as command operators. Leave off when arguments carry data (file names, e-mail subjects): such characters are then refused, so data can never run a command. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `process_id` | Number | Process id, used by Stop program. |

*Time limit 60 s; needs: desktop, filesystem.*

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

### Write to command session

`ooa.application/write_session` (action)

Send a line of text, or a password from the vault, to the program of a command session, as if typed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `cmd` | Main | Name of the command session, from Open command session. |
| Text | `text` | Text | No |  | Main; choice What to send = Text | Text to send, e.g. a command or an answer to a question. Example: `dir /b`; Shown when: `{"secret": [""]}` |
| Secret | `secret` | Credential (vault) | No |  | Main; choice What to send = Secret from the vault | A Credential to send instead, e.g. at a password prompt. It is never logged, and if the program prints it back, reads show **** instead. Shown when: `{"text": [""]}` |
| Press Enter afterwards | `press_enter` | Yes/No | No | Yes | Main | End with Enter, so the program takes the line. |

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
