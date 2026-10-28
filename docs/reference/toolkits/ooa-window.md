# Window

Toolkit `ooa.window`, version 1.0.0. Find, activate, move, resize, minimise, maximise, close and capture application windows on Windows.

[All toolkits](README.md)

## Contents

- **Window**: Activate window, Capture window, Close window, Close all matching windows, Get active window title, Get window details, List windows, Maximise window, Minimise window, Move and resize window, Restore window, Set window title, Wait for window
- **Conditions**: Window exists, Window is active

## Window

### Activate window

`ooa.window/activate` (action)

Bring a window to the front (restoring it if minimised).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Full title of the activated window. |

*Time limit 60 s; needs: desktop.*

### Capture window

`ooa.window/capture` (action)

Save a PNG screenshot of one window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Path | `path` | File path | Yes |  | Main | PNG file to create. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing file; when false an existing file stops the step. |
| Activate | `activate` | Yes/No | No | No | More options | Bring the window to the front first so nothing covers it. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved image. |

*Time limit 60 s; needs: desktop, filesystem.*

### Close window

`ooa.window/close` (action)

Ask a window to close (like clicking its X; the program may ask to save).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Wait until closed (seconds) | `wait_closed_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to disappear; 0 does not wait. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `closed` | Yes/No | True when the window was gone by the end of wait_closed_seconds (always true when not waiting). |

*Time limit 60 s; needs: desktop.*

### Close all matching windows

`ooa.window/close_all` (action)

Ask every visible window whose title matches to close (like clicking each X); programs may still ask to save.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | Yes |  | Main | Text in the window titles (case-insensitive), or a regular expression when use_regex is true. Example: ` - Notepad` |
| Keep | `keep` | List | No |  | Main | Windows to leave open: text in their titles (case-insensitive), e.g. ["Invoices.xlsx"]. The agent's own windows are always kept. |
| Title is a regular expression | `use_regex` | Yes/No | No | No | More options | Treat title as a regular expression. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `closed` | List | Titles of the windows asked to close. |
| `count` | Number | Number of windows asked to close. |

*Time limit 60 s; needs: desktop.*

### Get active window title

`ooa.window/get_active` (action)

Return the title of the window in front.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Title of the foreground window. |
| `process_name` | Text | Program file name of the foreground window, e.g. EXCEL.EXE. |
| `class_name` | Text | Window class of the foreground window. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get window details

`ooa.window/get_info` (action)

Find a window and return its title, program, class, position, size and state, e.g. to keep it in a variable for later steps.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `window` | Dictionary | title, process_name, class_name, x, y, width, height, state (normal, minimized or maximized), active. |
| `width` | Number | Width in pixels. |
| `height` | Number | Height in pixels. |
| `x` | Number | Left edge in pixels. |
| `y` | Number | Top edge in pixels. |
| `state` | Text | normal, minimized or maximized. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### List windows

`ooa.window/list` (action)

List the titles of all visible windows.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `titles` | List | Window titles. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Maximise window

`ooa.window/maximize` (action)

Maximise a window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Full title of the window. |

*Time limit 60 s; needs: desktop.*

### Minimise window

`ooa.window/minimize` (action)

Minimise a window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Full title of the window. |

*Time limit 60 s; needs: desktop.*

### Move and resize window

`ooa.window/move_resize` (action)

Set a window's position and/or size in pixels; values left empty keep the window's current ones.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| X | `x` | Number | No |  | Main | Left; unchanged when empty. |
| Y | `y` | Number | No |  | Main | Top; unchanged when empty. |
| Width | `width` | Number | No |  | Main | Width; unchanged when empty. |
| Height | `height` | Number | No |  | Main | Height; unchanged when empty. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

*Time limit 60 s; needs: desktop.*

### Restore window

`ooa.window/restore` (action)

Restore a minimised or maximised window to its normal size.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Full title of the window. |

*Time limit 60 s; needs: desktop.*

### Set window title

`ooa.window/set_title` (action)

Change the title bar text of a window, e.g. to tell several copies of the same program apart.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| New title | `new_title` | Text | Yes |  | Main | New title text. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Wait up to this many seconds for the window to appear; 0 looks once. Range: 0 to  |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `previous_title` | Text | Title before the change. |

*Time limit 60 s; needs: desktop.*

### Wait for window

`ooa.window/wait_for` (action)

Wait until a window whose title contains the text (or the main window of a process) opens, or until it has closed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| State | `state` | Text | No | `open` | Main | open: wait until the window is there; close: wait until no window with this title is left. One of: `open`, `close` |
| Title | `title` | Text | No |  | Main | Text contained in the window title (case-insensitive): the first visible window whose title contains it is used, unlike desktop objects, which need the whole title. May be empty when process_id is given. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait. Range: 0 to  |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | Main | Fail the step when time runs out; when off, met is false and the agent carries on. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged. Shown when: `{"state": ["open"]}` |
| Process ID | `process_id` | Number | No |  | More options | Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Full title of the window that opened (or that was open when waiting for it to close). |
| `met` | Yes/No | True when the window opened or closed in time. |

*Time limit 3600 s; needs: desktop.*

## Conditions

Used in If, Loop while and Wait until steps.

### Window exists

`ooa.window/exists` (condition)

True when a visible window title contains the text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | Yes |  | Main | Text contained in the window title (case-insensitive). |

### Window is active

`ooa.window/is_active` (condition)

True when the window in front has a title containing the text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | Yes |  | Main | Text contained in the window title (case-insensitive). |
