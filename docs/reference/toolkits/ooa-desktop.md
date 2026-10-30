# Mouse, keyboard and screen

Toolkit `ooa.desktop`, version 1.1.0. Click, type, press keys, capture the screen, use the clipboard and show dialogs on the desktop.

[All toolkits](README.md)

## Contents

- **Screen**: Capture screen
- **Clipboard**: Clear clipboard, Get clipboard text, Copy to clipboard
- **Dialogs**: Message box, Prompt for file, Prompt for folder, Prompt for password, Prompt for value, Prompt for yes/no
- **Mouse**: Mouse click, Mouse move, Mouse scroll
- **Keyboard**: Press keys, Type text

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
| All screens | `all_screens` | Yes/No | No | No | More options | Capture every monitor together instead of the main one (when no region or window is given). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved image path. |

*Time limit 60 s; needs: desktop.*

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

*Time limit 3600 s; needs: desktop.*

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

*Time limit 3600 s; needs: desktop.*

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

## Mouse

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

## Keyboard

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
