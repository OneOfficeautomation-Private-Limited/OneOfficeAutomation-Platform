# Desktop objects

Toolkit `ooa.desktop_ui`, version 1.0.0. Capture controls in Windows applications and find them again by their UI Automation properties to click, type, read and select.

[All toolkits](README.md)

## Contents

- **Capture**: Find legacy (MSAA) objects, Find desktop objects, Get desktop object properties, Get desktop table, Legacy (MSAA) object action, Desktop object action, Pick desktop object, Wait for desktop object
- **Recorder**: Record desktop actions
- **Conditions**: Desktop object exists

## Capture

### Find legacy (MSAA) objects

`ooa.desktop_ui/find_legacy_objects` (action)

List the controls of an older application through MSAA (IAccessible), for programs UI Automation does not describe well: name, role, value, default action, state and position, each with its path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window title | `window_title` | Text | No |  | Main | Title of the application window: text it contains, or a pattern with * and ?. |
| Name | `name` | Text | No |  | Main | Name of the control (contains, or * and ?); empty for any. |
| Role | `role` | Text | No |  | Main | MSAA role, e.g. push button, editable text, list item, check box (contains, or * and ?). |
| Value | `value` | Text | No |  | Main | Value of the control (contains, or * and ?); empty for any. |
| Process | `process` | Text | No |  | More options | Program of the window, e.g. myerp.exe (optional, narrows the search). |
| Largest value | `max` | Number | No | `500` | More options | At most this many objects. Range: 1 to 5000 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `objects` | List | Matching objects: path, name, role, value, description, default_action, state, unavailable, checked, invisible, focusable, bounds. |
| `count` | Number | Number of matching objects. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Find desktop objects

`ooa.desktop_ui/find_objects` (action)

Return every control that matches the object's used properties (the index is ignored), with their recorded properties.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | How long to wait for at least one match, in seconds; 0 looks once. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `objects` | List | One dictionary of properties per matching control, in the order UI Automation lists them. |
| `count` | Number | Number of matching controls. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get desktop object properties

`ooa.desktop_ui/get_object_properties` (action)

Find a captured control and return all its current UI Automation properties and states.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `10` | More options | How long to wait for the object to appear, in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `properties` | Dictionary | Current properties: the recorded ones plus states such as IsReadOnly, ToggleState, IsSelected, ExpandCollapseState and SupportedPatterns. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get desktop table

`ooa.desktop_ui/get_table` (action)

Read a grid, table or detailed list view in a Windows application into a Data Table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured grid, table or list control. The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
| Maximum rows | `max_rows` | Number | No | `10000` | More options | Stop after this many rows. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `10` | More options | How long to wait for the object to appear, in seconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | The cell texts; columns are named after the grid's column headers. |
| `row_count` | Number | Number of rows read. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Legacy (MSAA) object action

`ooa.desktop_ui/legacy_object_action` (action)

Act on a control of an older application through MSAA: its default action (press a button, check a box), set or get its value, select it, or read its properties. Find it by name and role, or by path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window title | `window_title` | Text | No |  | Main | Title of the application window: text it contains, or a pattern with * and ?. |
| Name | `name` | Text | No |  | Main; choice Find the control by = Name and role | Name of the control (contains, or * and ?). |
| Role | `role` | Text | No |  | Main; choice Find the control by = Name and role | MSAA role, e.g. push button, editable text, list item, check box (contains, or * and ?). |
| Index | `index` | Number | No | `0` | Main; choice Find the control by = Name and role | Which match to use when several do, 0 for the first. Range: 0 to  |
| Path | `path` | Text | No |  | Main; choice Find the control by = Path | The object's path from Find legacy objects, instead of name and role. Example: `0.3.1` |
| Action | `action` | Text | No | `default_action` | Main | default_action presses, checks or opens it as the application defines; set_value types the value into it. One of: `default_action`, `set_value`, `get_value`, `select`, `get_properties` |
| Value | `value` | Text | No |  | Main | The value to set. Shown when: `{"action": ["set_value"]}` |
| Process | `process` | Text | No |  | More options | Program of the window, e.g. myerp.exe (optional, narrows the search). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `10` | More options | How long to wait for the window and the control, in seconds. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The value read (get_value), the properties (get_properties), or true. |
| `object` | Dictionary | The object acted on: path, name, role, value and the rest. |

*Time limit 600 s; needs: desktop.*

### Desktop object action

`ooa.desktop_ui/object_action` (action)

Find a captured control in a Windows application and act on it: click, set or read text, read properties, select, expand, toggle, check, list items, read a grid, wait for it or get its position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
| Action | `action` | Text | No | `click` | Main | What to do with the object. One of: `click`, `set_text`, `get_text`, `get_property`, `get_all_properties`, `select_item`, `expand`, `collapse`, `toggle`, `check`, `uncheck`, `invoke`, `focus`, `get_children`, `get_list_items`, `get_table`, `wait_appear`, `wait_disappear`, `scroll_into_view`, `get_coordinates` |
| Click type | `click_type` | Text | No | `left` | Main | click: which click to make. One of: `left`, `right`, `double`, `middle` |
| Text | `text` | Any | No |  | Main | set_text: the text to enter (a Credential is accepted and never logged). |
| Property | `property` | Text | No |  | Main | get_property: the property to read, e.g. Name, Value, IsEnabled, ToggleState, IsSelected, ExpandCollapseState. Example: `Value` |
| Item | `item` | Text | No |  | Main | select_item: the text of the item to select in a list, combo box, tree or tab control. |
| Click method | `click_method` | Text | No | `auto` | More options | click: pattern presses the control through UI Automation without moving the mouse (left click only); mouse moves the pointer and clicks; auto uses a pattern when the control offers one, else the mouse. One of: `auto`, `pattern`, `mouse` |
| Offset x | `offset_x` | Number | No | `0` | More options | click with the mouse: pixels right of the object's centre (negative for left). |
| Offset y | `offset_y` | Number | No | `0` | More options | click with the mouse: pixels below the object's centre (negative for above). |
| Clear first | `clear_first` | Yes/No | No | Yes | More options | set_text: remove the existing text first; false appends. |
| Text method | `text_method` | Text | No | `auto` | More options | set_text: set_value writes the value directly through UI Automation; type sends key presses one character at a time; auto writes directly when the control allows it, else types. One of: `auto`, `set_value`, `type` |
| Pause between keys (milliseconds) | `type_delay_ms` | Number | No | `0` | More options | set_text with typing: pause between characters in milliseconds. |
| Item index | `item_index` | Number | No |  | More options | select_item: the position of the item to select (0 is the first), used when item is empty. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `10` | More options | How long to wait for the object to appear, in seconds. For wait_disappear, how long to wait for it to go. |
| If not found | `if_not_found` | Text | No | `error` | More options | When the object is not there in time: error stops with an error; continue skips this step (result is empty, found is false) and carries on. One of: `error`, `continue` |
| Run in background | `run_in_background` | Yes/No | No | No | More options | Work through UI Automation only: the window is not brought to the front and the mouse and keyboard are not used, so the application can stay behind other windows or minimised. Clicks and text then need a control that supports it (a single left click; text set directly). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the action returns: text for get_text, a value for get_property, a dictionary for get_all_properties and get_coordinates, a list for get_children and get_list_items, a table for get_table, true/false for the wait actions. |
| `healed_by` | Text | Empty when the object was found by its used properties; otherwise the recorded property that found it (update the object). |
| `found` | Yes/No | False when the step was skipped because the object was not found (if_not_found = continue). |

*Time limit 600 s; needs: desktop.*

### Pick desktop object

`ooa.desktop_ui/pick_object` (action)

Design time: point at a control in any Windows application and press Ctrl+Shift+C. A red frame shows the control under the pointer. Returns the captured object, ready for the other Desktop objects actions.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | Main | How long to wait for the Ctrl+Shift+C press, in seconds. |
| Highlight | `highlight` | Yes/No | No | Yes | Main | Draw a red frame around the control under the pointer while picking. |
| Screenshot | `screenshot` | Yes/No | No | Yes | More options | Include a PNG image of the control (base64) in the object. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `object` | Dictionary | The captured object: technology, window, properties (with match and use), path, index and screenshot. |

*Time limit 900 s; needs: desktop.*

### Wait for desktop object

`ooa.desktop_ui/wait_for_object` (action)

Wait until a captured control appears, or until it disappears, in a Windows application.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
| State | `state` | Text | No | `appear` | Main | Wait for the object to appear or to disappear. One of: `appear`, `disappear` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait in seconds. |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | More options | Fail the step when the time runs out; false returns found = false instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when the wait ended in the requested state; false after a timeout when fail_on_timeout is false. |

*Time limit 3600 s; needs: desktop.*

## Recorder

### Record desktop actions

`ooa.desktop_ui/record` (action)

Design time: records clicks and typing in any Windows application until Stop recording is pressed on the recording bar. Each click or field becomes a Desktop object action step with the full captured object; password fields are never read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Timeout (seconds) | `timeout_seconds` | Number | No | `1800` | Main | The longest the recording may run, in seconds. |
| Screenshot | `screenshot` | Yes/No | No | Yes | More options | Include a PNG image of each control (base64) in its object. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `steps` | List | The recorded steps (ooa.desktop_ui/object_action), ready to insert into an agent. |
| `count` | Number | Number of recorded steps. |

*Time limit 3600 s; needs: desktop.*

## Conditions

Used in If, Loop while and Wait until steps.

### Desktop object exists

`ooa.desktop_ui/object_exists` (condition)

True when the captured control can be found now in a Windows application (self-healing applies).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index. |
