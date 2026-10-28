# System

Toolkit `ooa.system`, version 1.0.0. Read environment variables and runner information; lock, sign out, restart or shut down the runner.

[All toolkits](README.md)

## Contents

- **System**: Get environment variable, Get all environment variables, Get system info, Set environment variable
- **Power**: Lock computer, Sign out, Restart computer, Shut down computer

## System

### Get environment variable

`ooa.system/get_environment_variable` (action)

Read an environment variable of the runner; variables that look like secrets are refused.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Variable name, e.g. COMPUTERNAME. |
| Default | `default` | Text | No |  | Main; tick Use a fallback value | Value when the variable is not set. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | Value, or the default when not set. |

*Time limit 60 s; safe to repeat.*

### Get all environment variables

`ooa.system/get_environment_variables` (action)

Return every environment variable of the runner as a dictionary; variables that look like secrets are left out.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `variables` | Dictionary | Variable name to value, sorted by name. |
| `hidden_count` | Number | How many secret-looking variables were left out. |

*Time limit 60 s; safe to repeat.*

### Get system info

`ooa.system/get_system_info` (action)

Return the runner name, operating system, user, processors and Java version.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | hostname, os, os_version, user, processors, java_version. |

*Time limit 60 s; safe to repeat.*

### Set environment variable

`ooa.system/set_environment_variable` (action)

Set an environment variable for programs this run starts (process scope) or permanently for the runner's user (user scope, Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Variable name: letters, digits and underscores. Example: `REPORT_FOLDER` |
| Value | `value` | Text | No |  | Main | Value; empty removes the variable. |
| Scope | `scope` | Text | No | `process` | Main | process: programs and scripts started later in this run; user: saved for the runner's Windows user. One of: `process`, `user` |

*Time limit 60 s.*

## Power

### Lock computer

`ooa.system/lock` (action)

Lock the screen of the current session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to lock. |

*Time limit 60 s; needs: desktop.*

### Sign out

`ooa.system/log_off` (action)

Sign out the current Windows user (open programs are closed).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to sign out. |
| Force | `force` | Yes/No | No | No | More options | Close programs without asking them to save (unsaved work is lost). |

*Time limit 60 s; needs: desktop.*

### Restart computer

`ooa.system/restart` (action)

Restart the runner after a delay.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to restart. |
| Delay (seconds) | `delay_seconds` | Number | No | `30` | More options | Seconds before restarting. Range: 0 to 315360000 |
| Force | `force` | Yes/No | No | No | More options | Close programs without asking them to save (unsaved work is lost). |
| Message | `message` | Text | No |  | More options | Reason shown to signed-in users and written to the Windows event log. |

*Time limit 60 s.*

### Shut down computer

`ooa.system/shut_down` (action)

Shut the runner down after a delay.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to shut down. |
| Delay (seconds) | `delay_seconds` | Number | No | `30` | More options | Seconds before shutting down. Range: 0 to 315360000 |
| Force | `force` | Yes/No | No | No | More options | Close programs without asking them to save (unsaved work is lost). |
| Message | `message` | Text | No |  | More options | Reason shown to signed-in users and written to the Windows event log. |

*Time limit 60 s.*
