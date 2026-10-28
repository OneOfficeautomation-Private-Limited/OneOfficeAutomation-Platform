# Scripts

Toolkit `ooa.script`, version 1.0.0. Run PowerShell, Python, JavaScript and VBScript with arguments and captured output.

[All toolkits](README.md)

## Contents

- **Scripts**: Run JavaScript, Run JavaScript function, Run PowerShell, Run Python, Run Python function, Run VBScript, Run VBScript function

## Scripts

### Run JavaScript

`ooa.script/run_javascript` (action)

Run a JavaScript file with Node.js.

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
| Interpreter | `interpreter` | Text | No |  | More options | Node.js program to use; node on the PATH when empty. Example: `node` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `output` | Text | Standard output. |
| `error_output` | Text | Standard error. |
| `exit_code` | Number | Exit code. |
| `json` | Any | Output parsed as JSON, when it is JSON. |

*Time limit 3600 s.*

### Run JavaScript function

`ooa.script/run_javascript_function` (action)

Call one function in a JavaScript file with Node.js, passing JSON-compatible arguments, and return what it returns (promises are awaited).

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

*Time limit 3600 s.*

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

*Time limit 3600 s.*

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

*Time limit 3600 s.*

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

*Time limit 3600 s.*

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

*Time limit 3600 s.*

### Run VBScript function

`ooa.script/run_vbscript_function` (action)

Call one function in a VBScript file with text arguments and return its result as text (Windows only).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Code = Use a file | VBScript file (.vbs) that defines the function. Shown when: `{"script": [""]}` |
| Script | `script` | Text | No |  | Main; choice Code = Type it here | Code that defines the function, typed in the agent, instead of a file. Shown when: `{"file": [""]}` |
| Function | `function` | Text | Yes |  | Main | Function name. Example: `FormatInvoice` |
| Arguments | `arguments` | List | No |  | Main | Arguments, in order; each is passed as text. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the function raises an error; when false the error is returned in error. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Value returned by the function, as text. |
| `output` | Text | Other text the script wrote. |
| `error` | Text | Error raised by the function when fail_on_error is false; empty on success. |

*Time limit 3600 s.*
