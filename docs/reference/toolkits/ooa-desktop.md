# Mouse, keyboard and screen

Toolkit `ooa.desktop`, version 1.1.0. Click, type, press keys, capture the screen, use the clipboard and show dialogs on the desktop.

[All toolkits](README.md)

## Contents

- **Keyboard**: Block input, Press keys, Set Caps Lock, Num Lock or Scroll Lock, Type text, Unblock input, Wait for mouse or key
- **Screen**: Capture screen
- **Clipboard**: Clear clipboard, Get clipboard text, Copy to clipboard
- **Mouse**: Get mouse position, Mouse click, Mouse move, Mouse scroll
- **Dialogs**: Message box, Prompt for file, Prompt for folder, Prompt for password, Prompt for value, Prompt for yes/no, Show callout

## Keyboard

### Block input

`ooa.desktop/block_input` (action)

Stop the person's mouse and keyboard from interfering while the next steps run. Windows allows it only to agents running as administrator; input comes back after the time limit, with Unblock input, or when the run ends, and Ctrl+Alt+Del always works.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Seconds | `seconds` | Number | No | `30` | Main | Longest time to block, 1 to 300 seconds. Range: 1 to 300 |

*Time limit 60 s; needs: desktop.*

### Press keys

`ooa.desktop/press_keys` (action)

Press, hold down or release a key or key combination, e.g. ENTER, CTRL+S or ALT+F4, in the active or a chosen window; repeat if needed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. |
| Keys | `keys` | Text | Yes |  | Main | Key or combination joined with +. Example: `CTRL+S` |
| Times | `times` | Number | No | `1` | Main | How many times to press. Range: 1 to  |
| Key action | `key_action` | Text | No | `press` | Main | press presses and releases; down holds the keys; up releases keys held by an earlier step. One of: `press`, `down`, `up` |
| Delay (milliseconds) | `delay_ms` | Number | No | `40` | More options | Pause after each press. Range: 0 to  |
| Hold keys down for (milliseconds) | `hold_ms` | Number | No | `0` | More options | How long to keep the keys down on each press. Range: 0 to  |

*Time limit 60 s; needs: desktop.*

### Set Caps Lock, Num Lock or Scroll Lock

`ooa.desktop/set_key_state` (action)

Turn Caps Lock, Num Lock or Scroll Lock on or off, so text typed later comes out as expected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Key | `key` | Text | Yes |  | Main | Which lock key. One of: `caps_lock`, `num_lock`, `scroll_lock` |
| State | `state` | Text | No | `off` | Main | Turn it on or off. One of: `on`, `off` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `was_on` | Yes/No | Whether the key was on before. |

*Time limit 60 s; needs: desktop.*

### Type text

`ooa.desktop/type_text` (action)

Type text, or a credential from the vault, into the active or a chosen window (text is pasted via the clipboard by default, which handles any language; a credential is always typed key by key and never logged); can include special keys such as [ENTER].

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. |
| Text | `text` | Text | No |  | Main; choice Type = Text | Text to type; used when no credential is given. |
| Special keys | `special_keys` | Yes/No | No | No | Main; choice Type = Text | Read keys in square brackets: [ENTER], [TAB 3], [CTRL+S], [SHIFT DOWN] ... [SHIFT UP]; [[ types a [. |
| Credential | `credential` | Credential (vault) | No |  | Main; choice Type = A credential from the vault | Credential attribute from the vault, e.g. a password; typed key by key (never through the clipboard) instead of text. |
| Method | `method` | Text | No | `paste` | Main | paste uses the clipboard (restored afterwards); keystrokes types letters and digits one by one. One of: `paste`, `keystrokes` |
| Delay (milliseconds) | `delay_ms` | Number | No | `20` | Main | Delay between keystrokes. Range: 0 to ; Shown when: `{"method": ["keystrokes"]}` |
| Restore clipboard | `restore_clipboard` | Yes/No | No | Yes | More options | With paste, put the previous clipboard content back afterwards. Shown when: `{"method": ["paste"]}` |
| Delay before (milliseconds) | `delay_before_ms` | Number | No | `0` | More options | Wait this long after bringing the window to the front, before typing. Range: 0 to  |

*Time limit 60 s; needs: desktop.*

### Unblock input

`ooa.desktop/unblock_input` (action)

Give the mouse and keyboard back after Block input, before its time limit.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `was_blocked` | Yes/No | True when input was blocked by this run. |

*Time limit 60 s; needs: desktop.*

### Wait for mouse or key

`ooa.desktop/wait_for_input` (action)

Pause until the person at the runner presses a chosen key, any key, or clicks the mouse, e.g. 'press F9 when the form is ready' in an attended agent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Wait for | `wait_for` | Text | No | `key` | Main | key: the key below; any_key: any key; click: any mouse button; key_or_click: the key below or a click. One of: `key`, `any_key`, `click`, `key_or_click` |
| Key | `key` | Text | No |  | Main | Key name, e.g. F9, ENTER, ESC or A. Example: `F9`; Shown when: `{"wait_for": ["key", "key_or_click"]}` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | Give up after this many seconds. Range: 1 to 3600 |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | No | More options | Fail with DESKTOP.INPUT_TIMEOUT when nothing happens in time; when off, timed_out is true and the agent carries on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `timed_out` | Yes/No | True when nothing happened in time. |
| `input` | Text | What happened: the key name, LEFT_CLICK, RIGHT_CLICK or MIDDLE_CLICK; empty on time-out. |
| `x` | Number | Pointer position (physical screen pixels) when it happened. |
| `y` | Number | Pointer position (physical screen pixels) when it happened. |

*Time limit 3700 s; needs: desktop.*

## Screen

### Capture screen

`ooa.desktop/capture_screen` (action)

Save a screenshot of the whole screen, all screens, a region or a window as a PNG, JPEG or BMP file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main; tick Capture one window only | Capture only this window (title text, pattern with * or ?, or re:regex); x and y are then measured from the window and default to its whole area. |
| X | `x` | Number | No |  | Main; tick Capture part of the screen | Region left (whole screen when empty). |
| Y | `y` | Number | No |  | Main; tick Capture part of the screen | Region top. |
| Width | `width` | Number | No |  | Main; tick Capture part of the screen | Region width. |
| Height | `height` | Number | No |  | Main; tick Capture part of the screen | Region height. |
| Path | `path` | File path | Yes |  | Main | Image file to save. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing file; when false an existing file stops the step. |
| Format | `format` | Text | No | `png` | More options | Image format. One of: `png`, `jpg`, `bmp` |
| Jpeg quality | `jpeg_quality` | Number | No | `90` | More options | JPEG quality from 1 (smallest file) to 100 (best picture). Range: 1 to 100; Shown when: `{"format": ["jpg"]}` |
| All screens | `all_screens` | Yes/No | No | No | More options | Capture every monitor together instead of the main one (when no region or window is given). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved image path. |

*Time limit 60 s; needs: desktop, filesystem.*

## Clipboard

### Clear clipboard

`ooa.desktop/clipboard_clear` (action)

Empty the clipboard.

No settings.

*Time limit 60 s; needs: desktop.*

### Get clipboard text

`ooa.desktop/clipboard_get` (action)

Read text from the clipboard, optionally waiting until text arrives (e.g. after pressing CTRL+C).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | More options | Keep checking up to this long while the clipboard is empty or busy; 0 reads once. Range: 0 to  |
| Trim | `trim` | Yes/No | No | No | More options | Remove spaces and line breaks at both ends. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Clipboard text, empty when it holds no text. |
| `has_text` | Yes/No | True when the clipboard held text. |

*Time limit 60 s; needs: desktop.*

### Copy to clipboard

`ooa.desktop/clipboard_set` (action)

Put text on the clipboard, replacing it or adding to what is there.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to copy. |
| Append | `append` | Yes/No | No | No | More options | Add the text after the current clipboard text instead of replacing it. |

*Time limit 60 s; needs: desktop.*

## Mouse

### Get mouse position

`ooa.desktop/get_mouse_position` (action)

Return where the mouse pointer is, in the same screen pixels Mouse click and Mouse move use.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `x` | Number | Pixels from the left of the main screen. |
| `y` | Number | Pixels from the top of the main screen. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Mouse click

`ooa.desktop/mouse_click` (action)

Click, double-click, or press or release a mouse button at a point on the screen or in a window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. |
| X | `x` | Number | Yes |  | Main | Horizontal position in pixels from the left. |
| Y | `y` | Number | Yes |  | Main | Vertical position in pixels from the top. |
| Button | `button` | Text | No | `left` | Main | Mouse button. One of: `left`, `right`, `middle` |
| Button action | `button_action` | Text | No | `click` | Main | click presses and releases; down only presses (hold, e.g. to start a drag); up only releases. One of: `click`, `down`, `up` |
| Double-click | `double_click` | Yes/No | No | No | Main | Double-click. |
| Relative to | `relative_to` | Text | No | `screen` | More options | screen: x and y are measured from the top-left of the screen; window: from the top-left of the chosen window. One of: `screen`, `window` |
| Modifier keys | `modifier_keys` | Text | No |  | More options | Keys held during the click, e.g. CTRL, SHIFT or CTRL+SHIFT. Example: `CTRL` |

*Time limit 60 s; needs: desktop.*

### Mouse move

`ooa.desktop/mouse_move` (action)

Move the pointer to a point on the screen or in a window, at once or gradually.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. |
| Relative to | `relative_to` | Text | No | `screen` | Main | screen: x and y are measured from the top-left of the screen; window: from the top-left of the chosen window. One of: `screen`, `window` |
| X | `x` | Number | Yes |  | Main | Horizontal position. |
| Y | `y` | Number | Yes |  | Main | Vertical position. |
| Duration (milliseconds) | `duration_ms` | Number | No | `0` | Main | Glide to the point over this many milliseconds; 0 jumps there at once. Range: 0 to  |
| From x | `from_x` | Number | No |  | More options; tick Start from another point | Put the pointer at this point first (with from_y, measured like x and y), then move to x, y; e.g. after pressing a button down, for a drag. |
| From y | `from_y` | Number | No |  | More options; tick Start from another point | Vertical position of the starting point (with from_x). |

*Time limit 60 s; needs: desktop.*

### Mouse scroll

`ooa.desktop/mouse_scroll` (action)

Scroll the mouse wheel up, down, left or right, optionally over a chosen point or window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Direction | `direction` | Text | No |  | Main | Scroll direction; when empty the sign of amount decides (positive scrolls down). Left and right hold SHIFT while scrolling. One of: `up`, `down`, `left`, `right` |
| Amount | `amount` | Number | Yes |  | Main | Wheel notches; negative scrolls up (or left) when direction is empty. |
| Delay (milliseconds) | `delay_ms` | Number | No | `0` | Main | Pause between notches; 0 scrolls all notches at once. Range: 0 to  |
| X | `x` | Number | No |  | More options | Move the pointer here before scrolling (with y). |
| Y | `y` | Number | No |  | More options | Vertical position to scroll at (with x). |
| Window | `window` | Text | No |  | More options | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. |
| Relative to | `relative_to` | Text | No | `screen` | More options | screen: x and y are measured from the top-left of the screen; window: from the top-left of the chosen window. One of: `screen`, `window` |

*Time limit 60 s; needs: desktop.*

## Dialogs

### Message box

`ooa.desktop/message_box` (action)

Show a message to the user (attended agents) with a choice of buttons and icon; can close automatically after a timeout.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `ONEOFFICEAUTOMATION` | Main | Window title. |
| Message | `message` | Text | Yes |  | Main | Message text. |
| Scroll after lines | `scroll_after_lines` | Number | No | `0` | Main | Show the message in a scrolling box when it has more lines than this; 0 never scrolls. Range: 0 to  |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | Main; tick Close it automatically | Close automatically after this many seconds; 0 waits for OK. Range: 0 to  |
| Buttons | `buttons` | Text | No | `ok` | More options | Buttons to show. One of: `ok`, `ok_cancel`, `yes_no`, `yes_no_cancel` |
| Icon | `icon` | Text | No | `information` | More options | Icon next to the message. One of: `information`, `warning`, `error`, `question`, `none` |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `button` | Text | Button chosen: ok, cancel, yes or no; closed when the window was closed; timeout when it closed by itself. |

*Time limit 3600 s; needs: desktop.*

### Prompt for file

`ooa.desktop/prompt_file` (action)

Ask the user to choose one or more files, optionally of given types.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `Choose a file` | Main | Dialog title. |
| Message | `message` | Text | No |  | Main | Text shown in the dialog above the list, e.g. what to choose and why. Example: `Pick this month's bank statement.` |
| File types | `file_types` | Text | No |  | Main | Only show files with these extensions; all files when empty. Example: `pdf, xlsx` |
| Multiple | `multiple` | Yes/No | No | No | Main | Let the user choose several files. |
| Start folder | `start_folder` | File path | No |  | More options | Folder to start in. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Give up after this many seconds; 0 waits. Range: 0 to  |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Chosen file (the first one when several), empty when cancelled. |
| `paths` | List | All chosen files. |
| `cancelled` | Yes/No | True when cancelled or timed out. |

*Time limit 3600 s; needs: desktop, filesystem.*

### Prompt for folder

`ooa.desktop/prompt_folder` (action)

Ask the user to choose a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `Choose a folder` | Main | Dialog title. |
| Message | `message` | Text | No |  | Main | Text shown in the dialog above the list, e.g. what to choose and why. Example: `Pick the folder with the scanned invoices.` |
| Start folder | `start_folder` | File path | No |  | More options | Folder to start in. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Give up after this many seconds; 0 waits. Range: 0 to  |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Chosen folder, empty when cancelled. |
| `cancelled` | Yes/No | True when cancelled or timed out. |

*Time limit 3600 s; needs: desktop, filesystem.*

### Prompt for password

`ooa.desktop/prompt_password` (action)

Ask the user for a password; the result is a Credential that is never shown or logged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `ONEOFFICEAUTOMATION` | Main | Window title of the dialog. |
| Question | `question` | Text | No | `Enter password` | Main | Prompt text. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Stop waiting after this many seconds (the step then fails as cancelled); 0 waits. Range: 0 to  |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `password` | Credential (vault) | Entered password as a Credential. |

*Time limit 3600 s; needs: desktop.*

### Prompt for value

`ooa.desktop/prompt_value` (action)

Ask the user to type a value, optionally hidden as it is typed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `ONEOFFICEAUTOMATION` | Main | Window title of the dialog. |
| Question | `question` | Text | Yes |  | Main | Question to show. |
| Masked | `masked` | Yes/No | No | No | Main | Show dots instead of the typed characters. The value is still plain text; use Prompt for password for secrets. |
| Default | `default` | Text | No |  | More options | Pre-filled answer. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Give up after this many seconds; 0 waits. Range: 0 to  |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | Entered text. |
| `cancelled` | Yes/No | True when the user cancelled or the prompt timed out. |
| `timed_out` | Yes/No | True when nobody answered in time; value is then the default. |

*Time limit 3600 s; needs: desktop.*

### Prompt for yes/no

`ooa.desktop/prompt_yes_no` (action)

Ask the user a yes/no question.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Title | `title` | Text | No | `ONEOFFICEAUTOMATION` | Main | Window title of the dialog. |
| Question | `question` | Text | Yes |  | Main | Question to show. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Answer No after this many seconds; 0 waits. Range: 0 to  |
| Answer on timeout | `answer_on_timeout` | Yes/No | No | No | More options | Answer used when the prompt times out or is closed without a choice. Shown when: `{"timeout_seconds": ["!", "0"]}` |
| Always on top | `always_on_top` | Yes/No | No | Yes | More options | Keep the dialog in front of other windows. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `answer` | Yes/No | True for Yes. |
| `timed_out` | Yes/No | True when nobody answered before the timeout (or the window was closed). |

*Time limit 3600 s; needs: desktop.*

### Show callout

`ooa.desktop/show_callout` (action)

Show a small note pointing at a place on the screen for a few seconds, e.g. to tell the person in an attended run which field the agent is about to fill. It takes no clicks and does not take the focus.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text of the note; a few words work best. Example: `Filling in the invoice number here` |
| X | `x` | Number | No |  | Main; choice Point at = A point | Point the note at: pixels from the left; the mouse pointer when x and y are empty. |
| Y | `y` | Number | No |  | Main; choice Point at = A point | Point the note at: pixels from the top. |
| Relative to | `relative_to` | Text | No | `screen` | Main; choice Point at = A point | screen: x and y are measured from the top-left of the screen; window: from the top-left of the chosen window. One of: `screen`, `window` |
| Window | `window` | Text | No |  | Main; choice Point at = A point | Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front. Shown when: `{"relative_to": ["window"]}` |
| Seconds | `seconds` | Number | No | `3` | Main | How long the note shows. Range: 1 to 60 |
| Wait | `wait` | Yes/No | No | No | More options | Wait until the note has gone before the next step; by default the agent carries on while it shows. |
| Color | `color` | Text | No | `yellow` | More options | Colour of the note. One of: `yellow`, `blue`, `green`, `red` |

*Time limit 120 s; needs: desktop.*
