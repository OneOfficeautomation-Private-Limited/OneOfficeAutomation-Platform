# Interactive forms

Toolkit `ooa.forms`, version 1.0.0. Show a form to the person at the runner, react to its buttons and changes, and read or change its fields while it is open.

[All toolkits](README.md)

## Contents

- **Interactive forms**: Assign options, Change label, Change form title, Close form, Disable element, Display form, Set dynamic area, Enable element, Get field value, Hide element, Highlight element, Reset form, Set field value, Set focus, Show element, Remove highlight, Validate form, Wait for form event

## Interactive forms

### Assign options

`ooa.forms/assign` (action)

Fill a drop-down or choice list with options, e.g. from a list or a data table column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Id of a drop-down or choice field. |
| Options | `options` | List | Yes |  | Main | The options to offer, in order. |
| Select | `select` | Text | No |  | Main | Option to select; the current choice is kept when it is still offered. |
| Mode | `mode` | Text | No | `replace` | More options | replace: offer only these options. append: add them after the options already offered, skipping ones already there. One of: `replace`, `append` |

*Time limit 60 s; needs: desktop.*

### Change label

`ooa.forms/change_label` (action)

Change the label of a field or the text of a button or heading.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |
| Label | `label` | Text | Yes |  | Main | The new label. |

*Time limit 60 s; needs: desktop.*

### Change form title

`ooa.forms/change_title` (action)

Change the title shown on the form window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Title | `title` | Text | Yes |  | Main | The new title. |

*Time limit 60 s; needs: desktop.*

### Close form

`ooa.forms/close` (action)

Close the open form window.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | The last answers by field id. |

*Time limit 60 s; needs: desktop.*

### Disable element

`ooa.forms/disable` (action)

Grey out a field or button so it cannot be used (a disabled field is not checked).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Display form

`ooa.forms/display` (action)

Show a form from the form designer to the person at the runner. Wait until they submit it, press a button or close it; or keep it open and go on, reacting to it with Wait for form event.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Form | `form` | File path | Yes |  | Main | The form (.form.json) made in Studio's form designer. Example: `Forms/Leave request.form.json` |
| Name | `name` | Text | No |  | More options | Name to refer to the open form by; the form's id when empty. |
| Title | `title` | Text | No |  | More options | Window title; the form's name when empty. |
| Values | `values` | Dictionary | No |  | More options | Values to fill in first, by field id (the designer's defaults otherwise). |
| Wait | `wait` | Text | No | `until_done` | More options | until_done: wait until the form is submitted, a button is pressed or it is closed. no: keep it open and go on. One of: `until_done`, `no` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | How long to wait for the person. Range: 1 to 86400; Shown when: `{"wait": ["until_done"]}` |
| Check answers | `check_answers` | Yes/No | No | Yes | More options | Check required fields and limits in the window before it can be submitted. |
| Width | `width` | Number | No | `560` | More options | Window width in pixels. Range: 320 to 2000 |
| Height | `height` | Number | No | `640` | More options | Window height in pixels. Range: 240 to 2000 |
| Always on top | `always_on_top` | Yes/No | No | No | More options | Keep the form window above other windows while it is open (Windows). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | The answers by field id (numbers as Number, checkboxes as true/false). |
| `button` | Text | How the wait ended: submit, the id of the button pressed, closed, or timeout. Empty when not waiting. |
| `submitted` | Yes/No | True when the person submitted the form. |
| `name` | Text | Name of the open form, for the other form actions. |
| `address` | Text | Local address of the form window (this computer only). |

*Time limit 86400 s; needs: desktop.*

### Set dynamic area

`ooa.forms/dynamic_area` (action)

Replace what a dynamic area on the form shows with new fields, e.g. one line per invoice found. Their answers are read like any other field.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Id of a dynamic area on the form. |
| Fields | `fields` | List | Yes |  | Main | Fields as Dictionaries with id, type (text, number, checkbox, dropdown, paragraph, button ...), label and optionally options, default, required. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of fields now in the area. |

*Time limit 60 s; needs: desktop.*

### Enable element

`ooa.forms/enable` (action)

Let the person use a field or button again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Get field value

`ooa.forms/get` (action)

Read one field's current value, or all answers when no field is given.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | No |  | Main | Field or button id on the form, as set in the form designer. Empty for all answers. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | The field's value (all answers as a Dictionary when no field is given). |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Hide element

`ooa.forms/hide` (action)

Hide a field, button or area (a hidden field is not checked).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Highlight element

`ooa.forms/highlight` (action)

Draw attention to a field or button with a coloured outline, in your own colour or as a warning or error, optionally with a message under it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |
| Kind | `kind` | Text | No | `color` | Main | color: the colour below. warning: amber. error: red. One of: `color`, `warning`, `error` |
| Color | `color` | Text | No | `#d97706` | Main | Outline colour, e.g. #d97706 or red. Shown when: `{"kind": ["color"]}` |
| Message | `message` | Text | No |  | Main | Text shown under the element in the outline colour; none when empty. |

*Time limit 60 s; needs: desktop.*

### Reset form

`ooa.forms/reset` (action)

Put every field back to its designer default (or empty) and clear the messages.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |

*Time limit 60 s; needs: desktop.*

### Set field value

`ooa.forms/set` (action)

Change a field's value on the open form.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |
| Value | `value` | Any | Yes |  | Main | New value (text, number, true/false for a checkbox, an option for a drop-down). |

*Time limit 60 s; needs: desktop.*

### Set focus

`ooa.forms/set_focus` (action)

Put the cursor in a field (or on a button).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Show element

`ooa.forms/show` (action)

Show a hidden field, button or area.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Remove highlight

`ooa.forms/unhighlight` (action)

Remove the highlight from a field or button.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Element | `element` | Text | Yes |  | Main | Field or button id on the form, as set in the form designer. |

*Time limit 60 s; needs: desktop.*

### Validate form

`ooa.forms/validate` (action)

Check the answers (required fields, number limits, email addresses, choices) and, if wanted, show the problems next to the fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Show messages | `show_messages` | Yes/No | No | Yes | More options | Show the problems on the form. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when there are no problems. |
| `errors` | Dictionary | Problems by field id, e.g. {"email": "Required"}. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Wait for form event

`ooa.forms/wait_for_event` (action)

Wait until the person presses a button, changes a field, submits or closes the open form; then react to it (e.g. in an If on the event and element).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Name of the open form (by default the form's id, as given when it was displayed). |
| Events | `events` | Text | No | `any` | Main | Which events to wait for (closing the form always ends the wait). One of: `any`, `button`, `change`, `submit` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | Main | How long to wait. Range: 1 to 86400 |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | No | More options | Stop with FORMS.TIMEOUT when nothing happened in time (otherwise timed_out is true). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `event` | Text | button, change, submit, close or timeout. |
| `element` | Text | Id of the button pressed or the field changed; empty for submit, close and timeout. |
| `value` | Any | The changed field's new value. |
| `values` | Dictionary | All answers by field id. |
| `timed_out` | Yes/No | True when nothing happened in time. |

*Time limit 86400 s; needs: desktop.*
