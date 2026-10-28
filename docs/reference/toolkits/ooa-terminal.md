# Terminal emulator (TN3270, TN5250, VT over SSH)

Toolkit `ooa.terminal`, version 1.2.0. Connect to IBM mainframe 3270 screens, IBM i (AS/400) 5250 screens or VT100/ANSI hosts over SSH, type into fields, press Enter and function keys, and read screen text.

[All toolkits](README.md)

## Contents

- **Terminal**: Clear screen, Connect, Disconnect, Get cursor position, Get screen text, Get text at position, Hide terminal, Press key, Type text, Set cursor, Set key mapping, Show terminal, Wait for screen, Wait for text
- **Fields**: Get all fields, Get field, Find text on screen, Set field

## Terminal

### Clear screen

`ooa.terminal/clear_screen` (action)

Erase all input fields locally, or send the Clear key to the host.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Send to host | `send_to_host` | Yes/No | No | No | More options | Send the Clear key to the host (blanks the whole screen) instead of only erasing input fields. |

*Time limit 120 s.*

### Connect

`ooa.terminal/connect` (action)

Connect to a TN3270 or TN5250 host, or sign in to a VT100/ANSI host over SSH, and wait for the first screen.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Host name or address. |
| Port | `port` | Number | No |  | Main | Port; 23 for 3270 and 5250 (992 is common with TLS) and 22 for SSH when empty. Range: 1 to 65535 |
| Terminal type | `terminal_type` | Text | No | `tn3270e` | Main | tn3270e (extended 3270, the usual mainframe choice), plain tn3270 for hosts that refuse the extensions, tn5250 for IBM i (AS/400) hosts, or vt-ssh for a VT100/ANSI host reached over SSH. One of: `tn3270e`, `tn3270`, `tn5250`, `vt-ssh` |
| Encrypted connection (TLS) | `tls` | Yes/No | No | No | Main | Use a TLS-encrypted connection. Shown when: `{"terminal_type": ["!", "vt-ssh"]}` |
| Model | `model` | Text | No | `3279-2` | Main | Screen model. 3270: 2 = 24x80, 3 = 32x80, 4 = 43x80, 5 = 27x132; 3278 is monochrome. 5250: 3179-2 = 24x80, 3477-FC = 27x132 (a 3270 model 5 also means 27x132). One of: `3279-2`, `3279-3`, `3279-4`, `3279-5`, `3278-2`, `3278-3`, `3278-4`, `3278-5`, `3179-2`, `3477-FC`; Shown when: `{"terminal_type": ["!", "vt-ssh"]}` |
| Code page | `code_page` | Text | No |  | Main | Host code page, e.g. cp037 (US), cp273 (German), cp500; the engine default (5250: cp037) when empty. Example: `cp037`; Shown when: `{"terminal_type": ["!", "vt-ssh"]}` |
| User | `user` | Text | No |  | Main | SSH user name. Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Password | `password` | Credential (vault) | No |  | Main | SSH password from the vault; leave empty when signing in with a key file. Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Key file | `key_file` | File path | No |  | Main; tick Sign in with a key file | Private key file for SSH sign-in (instead of, or as well as, a password). Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Key passphrase | `key_passphrase` | Credential (vault) | No |  | Main; tick Sign in with a key file | Passphrase of the key file, when it has one. Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Lu name | `lu_name` | Text | No |  | Main; tick Ask for a specific device name | Device name to request from the host (3270 logical unit, or 5250 display device); any free one when empty. Shown when: `{"terminal_type": ["!", "vt-ssh"]}` |
| Wait for text | `wait_for_text` | Text | No |  | Main; tick Wait for text on the first screen | Text the first screen must show (e.g. a logon prompt) before the agent continues; no check when empty. Example: `USERID` |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Connect timeout (seconds) | `connect_timeout_seconds` | Number | No | `30` | More options | Seconds to wait for the connection and the first screen. |
| Wait for input field | `wait_for_input_field` | Yes/No | No | Yes | More options | Wait until the first screen has a field to type into (on SSH: until the host stops sending); turn off for hosts that show a read-only banner first. |
| Host key fingerprint | `host_key_fingerprint` | Text | No |  | More options | Expected SSH host fingerprint; the connection stops when the host shows a different one. Strongly recommended. Example: `SHA256:...`; Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Emulation | `emulation` | Text | No | `vt100` | More options | Terminal kind reported to the SSH host; it decides which control codes the host sends. One of: `vt100`, `vt220`, `xterm`, `ansi`; Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Screen rows | `screen_rows` | Number | No | `24` | More options | Screen height in rows for SSH hosts. Range: 5 to 255; Shown when: `{"terminal_type": ["vt-ssh"]}` |
| Screen columns | `screen_columns` | Number | No | `80` | More options | Screen width in columns for SSH hosts. Range: 20 to 255; Shown when: `{"terminal_type": ["vt-ssh"]}` |

*Time limit 120 s; needs: network.*

### Disconnect

`ooa.terminal/disconnect` (action)

Disconnect and close the terminal.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |

*Time limit 60 s.*

### Get cursor position

`ooa.terminal/get_cursor` (action)

Read the row and column where the cursor is.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `row` | Number | Cursor row (1-based). |
| `column` | Number | Cursor column (1-based). |

*Time limit 60 s; safe to repeat.*

### Get screen text

`ooa.terminal/get_screen` (action)

Read the whole screen as text, one line per row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| From row | `from_row` | Number | No | `1` | Main | First row to return (1-based). |
| To row | `to_row` | Number | No | `0` | Main | Last row to return; 0 for the bottom of the screen. |
| Trim lines | `trim_lines` | Yes/No | No | No | More options | Remove trailing spaces from each row. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Screen text. |
| `lines` | List | One item per row. |

*Time limit 60 s; safe to repeat.*

### Get text at position

`ooa.terminal/get_text` (action)

Read text from a row and column for a given length.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Row | `row` | Number | Yes |  | Main | Row (1-based). |
| Column | `column` | Number | Yes |  | Main | Column (1-based). |
| Length | `length` | Number | Yes |  | Main | Number of characters. |
| Trim | `trim` | Yes/No | No | Yes | More options | Remove leading and trailing spaces. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Text (trimmed unless trim is off). |

*Time limit 60 s; safe to repeat.*

### Hide terminal

`ooa.terminal/hide_terminal` (action)

Hide the terminal window; the scripting engine is always hidden, so nothing changes.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |

*Time limit 60 s.*

### Press key

`ooa.terminal/send_key` (action)

Press Enter, Tab, Clear, a PF or PA key, and wait for the host to answer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Key | `key` | Text | Yes |  | Main | Enter, Tab, BackTab, Clear, Reset, EraseEOF, EraseInput, Home, Newline, Up, Down, Left, Right, BackSpace, Delete, Insert, FieldEnd, Dup, FieldMark, Attn, SysReq, PF1 to PF24 or PA1 to PA3. 5250 (IBM i) hosts also take FieldExit, Help, Print, PageUp and PageDown (Roll Down and Roll Up), but not PA keys or FieldMark. VT (SSH) hosts also take Escape, End, PageUp, PageDown and Ctrl+letter, e.g. Ctrl+C. Example: `Enter` |
| Delay after (milliseconds) | `delay_after_ms` | Number | No | `0` | Main | Extra pause after the key, in milliseconds. |
| Wait for text | `wait_for_text` | Text | No |  | Main; tick Wait for text afterwards | Text that must appear on the screen after the key (waits up to timeout_seconds); no check when empty. |
| Wait for host | `wait_for_host` | Yes/No | No | Yes | More options | After keys that go to the host (Enter, PF, PA, Clear...), wait until it unlocks the keyboard. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | More options | Seconds to wait for the host to unlock the keyboard after the key. |
| Repeat | `repeat` | Number | No | `1` | More options | How many times to press the key, e.g. 3 x Tab. |

*Time limit 120 s.*

### Type text

`ooa.terminal/send_text` (action)

Type text at a row and column, or at the cursor; Credentials are typed without being logged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Text | `text` | Any | Yes |  | Main | Text, or a Credential variable for passwords. |
| Press key | `press_key` | Text | No |  | Main; tick Press a key after typing | Key to press after typing, e.g. Enter or PF8; none when empty. Example: `Enter` |
| Delay after (milliseconds) | `delay_after_ms` | Number | No | `0` | Main | Extra pause after typing (and the key), in milliseconds. |
| Wait for text | `wait_for_text` | Text | No |  | Main; tick Wait for text afterwards | Text that must appear on the screen afterwards (waits up to timeout_seconds); no check when empty. |
| Row | `row` | Number | No |  | More options | Row (1-based); the cursor position when empty. On VT (SSH) hosts the cursor is moved with arrow keys, which suits full-screen programs. |
| Column | `column` | Number | No |  | More options | Column (1-based). |
| Clear field | `clear_field` | Yes/No | No | No | More options | Erase the rest of the field first. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | More options | Seconds to wait for the host to unlock the keyboard after the key. |

*Time limit 120 s.*

### Set cursor

`ooa.terminal/set_cursor` (action)

Move the cursor to a row and column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Row | `row` | Number | Yes |  | Main | Row (1-based). |
| Column | `column` | Number | Yes |  | Main | Column (1-based). |

*Time limit 60 s.*

### Set key mapping

`ooa.terminal/set_key_mapping` (action)

Give keys friendly names for this session, e.g. Submit for Enter or Exit for PF3, usable in Press key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Mappings | `mappings` | Dictionary | Yes |  | Main | Name to key, e.g. {"Submit": "Enter"}; an empty key removes the name. Example: `{"Submit": "Enter", "Exit": "PF3"}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `mappings` | Dictionary | All names set in this session. |

*Time limit 60 s.*

### Show terminal

`ooa.terminal/show_terminal` (action)

Ask for a visible terminal window; the scripting engine has no window, so this only records a note in the log.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |

*Time limit 60 s.*

### Wait for screen

`ooa.terminal/wait` (action)

Wait until the host unlocks the keyboard, shows an input field, changes the screen, or a number of seconds passes.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Condition | `condition` | Text | No | `input_field` | Main | input_field: a typeable field is shown; unlock: keyboard unlocked (terminal ready); screen_change: the host sends new output; screen_blank: the screen is empty; cursor_at / cursor_leaves: the cursor reaches or leaves row and column; seconds: just pause. One of: `input_field`, `unlock`, `screen_change`, `screen_blank`, `cursor_at`, `cursor_leaves`, `seconds` |
| Row | `row` | Number | No |  | Main | Row (1-based) for cursor_at and cursor_leaves. |
| Column | `column` | Number | No |  | Main | Column (1-based) for cursor_at and cursor_leaves. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait (the pause length for seconds). |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | More options | Stop with an error on timeout; when off, met is false instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `met` | Yes/No | True when the condition was met in time (false only when fail_on_timeout is off). |

*Time limit 600 s.*

### Wait for text

`ooa.terminal/wait_for_text` (action)

Wait until text appears (or disappears) on the screen or at a position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Text | `text` | Text | Yes |  | Main | Text to wait for. |
| Wait until gone | `wait_until_gone` | Yes/No | No | No | Main | Wait until the text is no longer shown, e.g. a PLEASE WAIT message. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait. |
| Row | `row` | Number | No |  | More options; tick Look at one position only | Only look at text starting at this row (1-based); anywhere when empty. |
| Column | `column` | Number | No |  | More options; tick Look at one position only | Column (1-based) of that position. |
| Case sensitive | `case_sensitive` | Yes/No | No | Yes | More options | Match upper and lower case exactly. |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | More options | Stop with an error on timeout; when off, found is false instead. |
| Poll interval (milliseconds) | `poll_interval_ms` | Number | No | `500` | More options | How often to look at the screen. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when the condition was met in time (false only when fail_on_timeout is off). |

*Time limit 600 s.*

## Fields

### Get all fields

`ooa.terminal/get_all_fields` (action)

List every field on the screen with its position, length, protection and text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Only input fields | `only_input_fields` | Yes/No | No | No | Main | Return only unprotected (typeable) fields. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: index, row, column, length, protected, numeric, hidden, text. |
| `count` | Number | Number of fields. |

*Time limit 60 s; safe to repeat.*

### Get field

`ooa.terminal/get_field` (action)

Read one field, found by a screen position inside it or by the label just before it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Row | `row` | Number | No |  | Main; choice Find the field by = Position | Row of any position in the field (1-based). |
| Column | `column` | Number | No |  | Main; choice Find the field by = Position | Column of that position (1-based). |
| Label | `label` | Text | No |  | Main; choice Find the field by = Label | Label text shown before the field; used instead of row and column. |
| Index | `index` | Number | No |  | Main; choice Find the field by = Field number | Field number as listed by Get all fields (1-based); used instead of position or label. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Field text (trimmed; empty for hidden fields). |
| `field` | Dictionary | index, row, column, length, protected, numeric, hidden, text. |

*Time limit 60 s; safe to repeat.*

### Find text on screen

`ooa.terminal/search_field` (action)

Find where a piece of text appears on the screen.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Text | `text` | Text | Yes |  | Main | Text to look for. |
| Occurrence | `occurrence` | Number | No | `1` | More options | Which match to return when the text appears more than once. |
| Case sensitive | `case_sensitive` | Yes/No | No | Yes | More options | Match upper and lower case exactly. |
| Whole field | `whole_field` | Yes/No | No | No | More options | Only match a field whose whole (trimmed) text equals the search text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when the text was found. |
| `row` | Number | Row of the first character (0 when not found). |
| `column` | Number | Column of the first character (0 when not found). |
| `field_index` | Number | Number of the field holding the match, as in Get all fields (0 when not found or the screen has no fields). |
| `count` | Number | How many times the text appears on the screen. |

*Time limit 60 s; safe to repeat.*

### Set field

`ooa.terminal/set_field` (action)

Replace the contents of an input field, found by position, label or field number; Credentials are typed without being logged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `terminal` | Main | Session name. |
| Row | `row` | Number | No |  | Main; choice Find the field by = Position | Row of any position in the field (1-based). |
| Column | `column` | Number | No |  | Main; choice Find the field by = Position | Column of that position (1-based). |
| Label | `label` | Text | No |  | Main; choice Find the field by = Label | Label text shown before the field; the next input field after it is used. |
| Index | `index` | Number | No |  | Main; choice Find the field by = Field number | Field number as listed by Get all fields (1-based); used instead of position or label. |
| Text | `text` | Any | Yes |  | Main | Text, or a Credential variable for passwords. |
| Press key | `press_key` | Text | No |  | Main; tick Press a key after filling | Key to press after filling the field; none when empty. Example: `Enter` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | More options | Seconds to wait for the host to unlock the keyboard after the key. |

*Time limit 120 s.*
