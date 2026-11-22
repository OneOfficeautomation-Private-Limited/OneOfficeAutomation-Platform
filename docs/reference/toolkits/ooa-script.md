# Scripts

Toolkit `ooa.script`, version 1.0.0. Run PowerShell, Python, JavaScript and VBScript with arguments and captured output.

[All toolkits](README.md)

## Contents

- **Script sessions**: Call function in session, Close script session, Open script session, Run code in session
- **Scripts**: Run JavaScript, Run JavaScript function, Run PowerShell, Run Python, Run Python function, Run VBScript, Run VBScript function
- **Conditions**: Last script step succeeded, Script succeeded, Script session is open

## Script sessions

### Call function in session

`ooa.script/call_in_session` (action)

Call a function defined in an open script session with arguments, and return what it returns (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `script` | Main | Name of the script session, from Open script session. |
| Function | `function` | Text | Yes |  | Main | Function name; dots for a function of an object or module. Example: `total` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order. |
| Keyword arguments | `keyword_arguments` | Dictionary | No |  | Main | Python only: named arguments. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time; the session is closed when it runs out. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Stop the step when the function raises an error. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value the function returned. |
| `output` | Text | Text the function printed. |
| `error` | Text | The error when fail_on_error is off; empty on success. |

*Time limit 3600 s.*

### Close script session

`ooa.script/close_session` (action)

End a script session and its program (G4); sessions also end with the run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `script` | Main | Name of the script session, from Open script session. |

*Time limit 60 s.*

### Open script session

`ooa.script/open_session` (action)

Start Python, JavaScript (Node.js) or VBScript and keep it running for the steps after it: what one step defines is there for the next (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Language | `language` | Text | No | `python` | Main | The scripting language. One of: `python`, `javascript`, `vbscript` |
| Session | `session` | Text | No | `script` | Main | Session name the later steps use. |
| File | `file` | File path | No |  | Main; tick Load a file first | A script file to run first, e.g. one that defines functions. |
| Interpreter | `interpreter` | Text | No |  | More options | The program to use (python, node or cscript); found on the PATH when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables. |
| Share | `share` | Yes/No | No | No | More options | Agents this one runs use the same session. |

*Time limit 120 s; needs: filesystem.*

### Run code in session

`ooa.script/run_in_session` (action)

Run code, or a file, in an open script session; the value of a last expression (Python, JavaScript) or of the code (VBScript Eval, when run_as is eval) comes back (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `script` | Main | Name of the script session, from Open script session. |
| Script | `script` | Text | No |  | Main; choice Code = Type it here | Code to run. |
| File | `file` | File path | No |  | Main; choice Code = Run a file | Or a script file to run in the session. |
| Run as | `run_as` | Text | No | `exec` | Main | VBScript only: exec runs statements; eval works out an expression and returns its value. One of: `exec`, `eval` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time; the session is closed when it runs out. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Stop the step when the code raises an error. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The value of the last expression, when there is one. |
| `output` | Text | Text the code printed. |
| `error` | Text | The error when fail_on_error is off; empty on success. |

*Time limit 3600 s; needs: filesystem.*

## Scripts

### Run JavaScript

`ooa.script/run_javascript` (action)

Run JavaScript with Node.js, or with Windows' built-in JScript when Node.js is not installed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Script | `script` | Text | No |  | Main; choice Script = Type it here | Script text (or use file). Shown when: `{"file": [""]}` |
| File | `file` | File path | No |  | Main; choice Script = Use a file | Script file (or use script). Shown when: `{"script": [""]}` |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the script, one list item each. |
| Input | `input` | Text | No |  | More options | Text sent to the script's standard input (e.g. JSON). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the exit code is not 0. |
| Working folder | `working_folder` | File path | No |  | More options | Folder the script runs in; the workspace when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the script. |
| Interpreter | `interpreter` | Text | No |  | More options | Node.js program to use; node on the PATH when empty, and Windows' built-in JScript when Node.js is not installed. Example: `node` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |
| `exit_code` | Number | Exit code. |
| `json` | Any | Output parsed as JSON, when it is JSON. |

*Time limit 3600 s; needs: filesystem.*

### Run JavaScript function

`ooa.script/run_javascript_function` (action)

Call one function in a JavaScript file with Node.js (or Windows' built-in JScript when Node.js is not installed), passing JSON-compatible arguments, and return what it returns (promises are awaited with Node.js).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Code = Use a file | JavaScript file (.js) that defines or exports the function. Shown when: `{"script": [""]}` |
| Script | `script` | Text | No |  | Main; choice Code = Type it here | Code that defines the function, typed in the agent, instead of a file. Shown when: `{"file": [""]}` |
| Function | `function` | Text | Yes |  | Main | Function name; use object.method for a method of an exported object. Example: `calculateTotal` |
| Arguments | `arguments` | List | No |  | Main | Arguments, in order. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the function throws; when false the error is returned in error. |
| Interpreter | `interpreter` | Text | No |  | More options | Node.js program to use; node on the PATH when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value returned by the function (numbers, text, lists and dictionaries). |
| `output` | Text | Text the function printed. |
| `error` | Text | Error thrown by the function when fail_on_error is false; empty on success. |

*Time limit 3600 s; needs: filesystem.*

### Run PowerShell

`ooa.script/run_powershell` (action)

Run a PowerShell script (PowerShell 7 if installed, otherwise Windows PowerShell).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Script | `script` | Text | No |  | Main; choice Script = Type it here | Script text (or use file). Shown when: `{"file": [""]}` |
| File | `file` | File path | No |  | Main; choice Script = Use a file | Script file (or use script). Shown when: `{"script": [""]}` |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the script, one list item each. |
| Input | `input` | Text | No |  | More options | Text sent to the script's standard input (e.g. JSON). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the exit code is not 0. |
| Working folder | `working_folder` | File path | No |  | More options | Folder the script runs in; the workspace when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the script. |
| Interpreter | `interpreter` | Text | No |  | More options | PowerShell program to use, e.g. pwsh or powershell; PowerShell 7 first when empty. Example: `powershell` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |
| `exit_code` | Number | Exit code. |
| `json` | Any | Output parsed as JSON, when it is JSON. |

*Time limit 3600 s; needs: filesystem.*

### Run Python

`ooa.script/run_python` (action)

Run a Python 3 script.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Script | `script` | Text | No |  | Main; choice Script = Type it here | Script text (or use file). Shown when: `{"file": [""]}` |
| File | `file` | File path | No |  | Main; choice Script = Use a file | Script file (or use script). Shown when: `{"script": [""]}` |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the script, one list item each. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | Main | Fail the step when the exit code is not 0. |
| Input | `input` | Text | No |  | More options | Text sent to the script's standard input (e.g. JSON). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Working folder | `working_folder` | File path | No |  | More options | Folder the script runs in; the workspace when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the script. |
| Interpreter | `interpreter` | Text | No |  | More options | Python program to use, e.g. a virtual environment's python; found on the PATH when empty. Example: `C:/Python312/python.exe` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |
| `exit_code` | Number | Exit code. |
| `json` | Any | Output parsed as JSON, when it is JSON. |

*Time limit 3600 s; needs: filesystem.*

### Run Python function

`ooa.script/run_python_function` (action)

Call one function in a Python file with JSON-compatible arguments and return what it returns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Code = Use a file | Python file (.py) that defines the function. Shown when: `{"script": [""]}` |
| Script | `script` | Text | No |  | Main; choice Code = Type it here | Code that defines the function, typed in the agent, instead of a file. Shown when: `{"file": [""]}` |
| Function | `function` | Text | Yes |  | Main | Function name; use Class.method for a static method. Example: `calculate_total` |
| Arguments | `arguments` | List | No |  | Main | Positional arguments, in order. |
| Keyword arguments | `keyword_arguments` | Dictionary | No |  | Main | Named arguments. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | Main | Fail the step when the function raises an error; when false the error is returned in error and the step continues. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Interpreter | `interpreter` | Text | No |  | More options | Python program to use; found on the PATH when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for Python. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value returned by the function (numbers, text, lists and dictionaries). |
| `output` | Text | Text the function printed. |
| `error` | Text | Error raised by the function when fail_on_error is false; empty on success. |

*Time limit 3600 s; needs: filesystem.*

### Run VBScript

`ooa.script/run_vbscript` (action)

Run a VBScript with Windows Script Host (Windows only).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Script | `script` | Text | No |  | Main; choice Script = Type it here | Script text (or use file). Shown when: `{"file": [""]}` |
| File | `file` | File path | No |  | Main; choice Script = Use a file | Script file (or use script). Shown when: `{"script": [""]}` |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the script, one list item each. |
| Input | `input` | Text | No |  | More options | Text sent to the script's standard input (e.g. JSON). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the exit code is not 0. |
| Working folder | `working_folder` | File path | No |  | More options | Folder the script runs in; the workspace when empty. |
| Environment | `environment` | Dictionary | No |  | More options | Extra environment variables for the script. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |
| `exit_code` | Number | Exit code. |
| `json` | Any | Output parsed as JSON, when it is JSON. |

*Time limit 3600 s; needs: filesystem.*

### Run VBScript function

`ooa.script/run_vbscript_function` (action)

Call one function in a VBScript file and return its result as text (Windows only).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Code = Use a file | VBScript file (.vbs) that defines the function. Shown when: `{"script": [""]}` |
| Script | `script` | Text | No |  | Main; choice Code = Type it here | Code that defines the function, typed in the agent, instead of a file. Shown when: `{"file": [""]}` |
| Function | `function` | Text | Yes |  | Main | Function name. Example: `FormatInvoice` |
| Arguments | `arguments` | List | No |  | Main | Arguments, in order: numbers and true/false are passed as VBScript numbers and booleans, empty values as Empty, everything else as text. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the function raises an error; when false the error is returned in error. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Value returned by the function, as text. |
| `output` | Text | Other text the script wrote. |
| `error` | Text | Error raised by the function when fail_on_error is false; empty on success. |

*Time limit 3600 s; needs: filesystem.*

## Conditions

Used in If, Loop while and Wait until steps.

### Last script step succeeded

`ooa.script/last_script_succeeded` (condition)

True when the last script step succeeded: in this session, or (session empty) the last script step of the run (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No |  | Main | A script session; the last script step of the run when empty. |

### Script succeeded

`ooa.script/script_succeeded` (condition)

Run a PowerShell, Python, JavaScript or VBScript script and be true when it ends without an error (exit code 0).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Language | `language` | Text | No | `powershell` | Main | The script's language. One of: `powershell`, `python`, `javascript`, `vbscript` |
| Script | `script` | Text | No |  | Main | Script text (or use file). |
| File | `file` | File path | No |  | Main | Script file (or use script). |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the script, one list item each. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | Maximum run time. Range: 1 to  |

*Needs: filesystem.*

### Script session is open

`ooa.script/session_open` (condition)

True when the script session is open and its program still runs (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `script` | Main | Name of the script session, from Open script session. |
