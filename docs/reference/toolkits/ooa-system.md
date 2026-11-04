# System

Toolkit `ooa.system`, version 1.0.0. Read environment variables and runner information; lock, sign out, restart or shut down the runner.

[All toolkits](README.md)

## Contents

- **System**: Delete environment variable, Empty recycle bin, Get environment variable, Get all environment variables, Get screen resolution, Get system info, List processes, Desktop notification, Set environment variable, Set screen resolution, Show desktop
- **Power**: Lock computer, Sign out, Restart computer, Shut down computer

## System

### Delete environment variable

`ooa.system/delete_environment_variable` (action)

Remove an environment variable for programs this run starts (process scope) or permanently for the runner's user (user scope, Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Variable name. Example: `REPORT_FOLDER` |
| Scope | `scope` | Text | No | `process` | Main | process: programs and scripts started later in this run; user: removed for the runner's Windows user. One of: `process`, `user` |

*Time limit 60 s.*

### Empty recycle bin

`ooa.system/empty_recycle_bin` (action)

Delete everything in the recycle bin of one drive or of every drive. This cannot be undone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to empty the recycle bin. |
| Drive | `drive` | Text | No |  | More options | Drive letter; every drive when empty. Example: `D` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of items that were in the recycle bin. |

*Time limit 60 s.*

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

### Get screen resolution

`ooa.system/get_screen_resolution` (action)

Return the resolution and display scaling of the main screen, and of every screen.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `width` | Number | Main screen width in pixels, e.g. 1920. |
| `height` | Number | Main screen height in pixels, e.g. 1080. |
| `scale_percent` | Number | Main screen's display scaling, e.g. 125. |
| `screens` | List | Every screen: width, height, scale_percent, refresh_hz, primary. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get system info

`ooa.system/get_system_info` (action)

Return the runner name, operating system, user, processors and Java version.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | hostname, os, os_version, user, processors, java_version. |

*Time limit 60 s; safe to repeat.*

### List processes

`ooa.system/list_processes` (action)

List the running programs with their process id, memory use, start time and window title, optionally only those whose name contains a text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name contains | `name_contains` | Text | No |  | Main | Only processes whose name contains this text (any case); all when empty. Example: `excel` |
| With window only | `with_window_only` | Yes/No | No | No | More options | Only processes that show a window. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `processes` | List | Records with name, pid, memory_mb, started (UTC) and window_title, sorted by name. |
| `count` | Number | Number of processes listed. |

*Time limit 60 s; safe to repeat.*

### Desktop notification

`ooa.system/notify` (action)

Show a Windows notification with a title and a message to the person signed in on the runner; the agent does not wait.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | Yes |  | Main | Title in bold. Example: `Invoices done` |
| Message | `message` | Text | No |  | Main | Text under the title. Example: `42 invoices posted, 2 need checking.` |

*Time limit 60 s; needs: desktop.*

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

### Set screen resolution

`ooa.system/set_screen_resolution` (action)

Change the main screen's resolution for this Windows session (not saved), for agents that need a fixed screen size; put back when the run ends unless turned off.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Width | `width` | Number | Yes |  | Main | Width in pixels. Example: `1920`; Range: 640 to 7680 |
| Height | `height` | Number | Yes |  | Main | Height in pixels. Example: `1080`; Range: 480 to 4320 |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to change the screen. |
| Refresh hz | `refresh_hz` | Number | No |  | More options | Refresh rate, e.g. 60; the screen's choice when empty. Range: 1 to 500 |
| Restore at end | `restore_at_end` | Yes/No | No | Yes | More options | Put the saved resolution back when the run ends (also when it fails). |

*Time limit 60 s; needs: desktop.*

### Show desktop

`ooa.system/show_desktop` (action)

Minimise every window so the desktop shows, like Windows+D.

No settings.

*Time limit 60 s; needs: desktop.*

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
