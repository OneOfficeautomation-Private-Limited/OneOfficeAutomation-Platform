# PowerPoint (desktop, needs PowerPoint)

Toolkit `ooa.powerpoint_desktop`, version 1.0.0. Insert videos and files, paste the clipboard and run macros using the installed Microsoft PowerPoint.

[All toolkits](README.md)

## Contents

- **Media**: Insert file, Insert video, Paste into slide
- **Macros**: Run macro

## Media

### Insert file

`ooa.powerpoint_desktop/insert_file` (action)

Embed a file (an Excel sheet, a PDF, any file) on a slide as an icon that opens it when double-clicked in the slide show or editor.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Presentation (.pptx, .pptm, .ppt). |
| Slide | `slide` | Number | No | `1` | Main | Slide number, 1 for the first. Range: 1 to  |
| File | `file` | File path | Yes |  | Main | File to embed. |
| Label | `label` | Text | No |  | Main | Text under the icon; the file name when empty. |
| Left | `left` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Top | `top` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Width | `width` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Height | `height` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Save | `save` | Yes/No | No | Yes | More options | Save the presentation in place afterwards (when save_as is empty). |
| Save as | `save_as` | File path | No |  | More options | Save to this file instead of the opened one. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace save_as when it exists; false stops with POWERPOINT_DESKTOP.EXISTS. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `shape` | Text | Name of the new shape on the slide. |
| `position` | Dictionary | left, top, width and height of the shape in points. |

*Time limit 900 s; needs: desktop, filesystem.*

### Insert video

`ooa.powerpoint_desktop/insert_video` (action)

Put a video (MP4, WMV, MOV …) on a slide, embedded in the presentation, at a position and size.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Presentation (.pptx, .pptm, .ppt). |
| Slide | `slide` | Number | No | `1` | Main | Slide number, 1 for the first. Range: 1 to  |
| Video | `video` | File path | Yes |  | Main | Video file to embed. |
| Left | `left` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Top | `top` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Width | `width` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Height | `height` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Save | `save` | Yes/No | No | Yes | More options | Save the presentation in place afterwards (when save_as is empty). |
| Save as | `save_as` | File path | No |  | More options | Save to this file instead of the opened one. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace save_as when it exists; false stops with POWERPOINT_DESKTOP.EXISTS. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `shape` | Text | Name of the new shape on the slide. |
| `position` | Dictionary | left, top, width and height of the shape in points. |

*Time limit 900 s; needs: desktop, filesystem.*

### Paste into slide

`ooa.powerpoint_desktop/paste` (action)

Paste what is on the clipboard (a chart or range copied from Excel, a picture, text) onto a slide, optionally as a picture, and place it. PowerPoint only pastes into an open window, so a minimised PowerPoint window shows while it works.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Presentation (.pptx, .pptm, .ppt). |
| Slide | `slide` | Number | No | `1` | Main | Slide number, 1 for the first. Range: 1 to  |
| Paste as | `paste_as` | Text | No | `default` | Main | default pastes as PowerPoint would (a chart stays a chart); picture pastes a PNG picture of it; text pastes plain text. One of: `default`, `picture`, `text` |
| Left | `left` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Top | `top` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. |
| Width | `width` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Height | `height` | Number | No |  | Main; tick Position and size | In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty. Range: 1 to  |
| Save | `save` | Yes/No | No | Yes | More options | Save the presentation in place afterwards (when save_as is empty). |
| Save as | `save_as` | File path | No |  | More options | Save to this file instead of the opened one. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace save_as when it exists; false stops with POWERPOINT_DESKTOP.EXISTS. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `shape` | Text | Name of the pasted shape on the slide. |
| `position` | Dictionary | left, top, width and height of the shape in points. |

*Time limit 900 s; needs: desktop, filesystem.*

## Macros

### Run macro

`ooa.powerpoint_desktop/run_macro` (action)

Open a macro-enabled presentation, run a VBA macro with up to 5 text arguments, return its result and optionally save. A macro that stops with an error is reported straight away with its message.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Presentation with macros (.pptm, .ppsm, .ppt). |
| Macro | `macro` | Text | Yes |  | Main | Macro name, optionally with its module. Example: `Module1.BuildDeck` |
| Arguments | `arguments` | List | No |  | Main | Up to 5 arguments, passed to the macro as text. |
| Save | `save` | Yes/No | No | No | Main | Save the presentation in place afterwards (when save_as is empty). |
| Save as | `save_as` | File path | No |  | More options | Save to this file instead of the opened one. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace save_as when it exists; false stops with POWERPOINT_DESKTOP.EXISTS. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | More options | Maximum run time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Value the macro returned (for a Function), as text; empty for a Sub. |

*Time limit 3600 s; needs: desktop, filesystem.*
