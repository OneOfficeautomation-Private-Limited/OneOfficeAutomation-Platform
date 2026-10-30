# DLL

Toolkit `ooa.dll`, version 1.0.0. Call a function in a trusted native library (DLL) with numbers and text, and get its return value.

[All toolkits](README.md)

## Contents

- **DLL**: Call DLL function, Close DLL, Open DLL

## DLL

### Call DLL function

`ooa.dll/call` (action)

Call an exported function, e.g. user32.dll MessageBeep, with typed arguments; returns its value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Library | `library` | Text | No |  | Main; choice Library = By name or path | Library name or full path of the DLL (or use session). Example: `user32` |
| Session | `session` | Text | No |  | Main; choice Library = An open session | Session from Open DLL; used instead of library. |
| Function | `function` | Text | Yes |  | Main | Exported function name. Example: `MessageBeep` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order, each {"type": "int\|long\|double\|bool\|string\|wstring", "value": ...}. |
| Return type | `return_type` | Text | No | `int` | Main | Type of the return value. One of: `void`, `int`, `long`, `double`, `string`, `wstring` |
| Calling convention | `calling_convention` | Text | No | `c` | More options | How the function expects its arguments; matters only for 32-bit DLLs (Win32 API functions use stdcall). One of: `c`, `stdcall` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Return value (number or text; empty for void). |

*Time limit 120 s; needs: desktop.*

### Close DLL

`ooa.dll/close` (action)

Unload a library opened with Open DLL.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `dll` | Main | Session name. |

*Time limit 60 s; needs: desktop.*

### Open DLL

`ooa.dll/open` (action)

Load a trusted native library once as a named session, so several Call DLL function steps can use it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Library | `library` | Text | Yes |  | Main | Library name or full path of the DLL. Example: `C:/Tools/calc.dll` |
| Session | `session` | Text | No | `dll` | More options | Session name for later steps. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | Text | Name or path the library was loaded from. |

*Time limit 60 s; needs: desktop.*
