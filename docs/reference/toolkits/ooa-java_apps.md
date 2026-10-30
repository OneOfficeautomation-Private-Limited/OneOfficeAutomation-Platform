# Java applications (Swing/AWT)

Toolkit `ooa.java_apps`, version 1.0.0. Find and work with the controls of desktop applications written in Java (Swing and AWT): read and type text, press buttons, read labels and tables.

[All toolkits](README.md)

## Contents

- **Java applications**: Find Java objects, Get Java applications, Java object action

## Java applications

### Find Java objects

`ooa.java_apps/find_objects` (action)

List the controls of a Java application's window: name, role, text, state, actions and position, each with its path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window title | `window_title` | Text | No |  | Main | Title of the application's window: text it contains, or a pattern with * and ?. |
| Name | `name` | Text | No |  | Main | Name of the control (contains, or * and ?); empty for any. |
| Role | `role` | Text | No |  | Main | Java accessibility role, e.g. push button, text, label, check box, combo box, table (contains, or * and ?). |
| Process | `process` | Text | No |  | More options | Part of the Java program's name (its main class or jar, as Get Java applications lists it); narrows the search and is needed for programs named like tools. |
| Largest value | `max` | Number | No | `500` | More options | At most this many controls. Range: 1 to 5000 |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | How long to wait for at least one match, in seconds; 0 looks once. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `objects` | List | Matching controls: path, name, role, description, text, enabled, visible, editable, checked, actions, bounds, pid. |
| `count` | Number | Number of matching controls. |

*Time limit 600 s; safe to repeat; needs: desktop.*

### Get Java applications

`ooa.java_apps/list_apps` (action)

List the Java programs running as the runner's user that have windows, with their window titles.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process | `process` | Text | No |  | Main | Part of the Java program's name (its main class or jar, as Get Java applications lists it); narrows the search and is needed for programs named like tools. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `apps` | List | One dictionary per program: pid, name and windows (their titles). |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Java object action

`ooa.java_apps/object_action` (action)

Act on a control of a Java application: press it (its action), type text into it, read its text, give it the focus, or read its properties. Find it by name and role, or by path.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window title | `window_title` | Text | No |  | Main | Title of the application's window: text it contains, or a pattern with * and ?. |
| Name | `name` | Text | No |  | Main; choice Find the control by = Name and role | Name of the control (contains, or * and ?). |
| Role | `role` | Text | No |  | Main; choice Find the control by = Name and role | Java accessibility role, e.g. push button, text, label, check box, combo box, table (contains, or * and ?). |
| Index | `index` | Number | No | `0` | Main; choice Find the control by = Name and role | Which match to use when several do, 0 for the first. Range: 0 to  |
| Path | `path` | Text | No |  | Main; choice Find the control by = Path | The control's path from Find Java objects, instead of name and role. Example: `0.0.1.2` |
| Action | `action` | Text | No | `do_action` | Main | do_action presses a button, toggles a box or opens a menu; set_text replaces the text of a field. One of: `do_action`, `set_text`, `get_text`, `focus`, `get_properties` |
| Value | `value` | Text | No |  | Main | The text to type (set_text), or the name of the action to do when the control has several. |
| Process | `process` | Text | No |  | More options | Part of the Java program's name (its main class or jar, as Get Java applications lists it); narrows the search and is needed for programs named like tools. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `10` | More options | How long to wait for the control, in seconds. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The text read (get_text), the properties (get_properties), or true. |
| `object` | Dictionary | The control acted on: path, name, role, text and the rest. |

*Time limit 600 s; needs: desktop.*
