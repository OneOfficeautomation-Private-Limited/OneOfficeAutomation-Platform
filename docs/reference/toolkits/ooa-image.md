# Image recognition

Toolkit `ooa.image`, version 1.0.0. Find, wait for and click images on the screen or inside image files.

[All toolkits](README.md)

## Contents

- **Image**: Click image, Find image in image, Find image on screen, Wait for image, Wait for screen change
- **Conditions**: Image is in image file, Image is on screen

## Image

### Click image

`ooa.image/click_image` (action)

Find a picture on the screen (optionally waiting for it, in a region, or its n-th appearance) and click it with the chosen button at its centre, corner or an offset.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | File path | Yes |  | Main | Picture to click. |
| Double-click | `double_click` | Yes/No | No | No | Main | Double-click. |
| Button | `button` | Text | No | `left` | Main | Mouse button to click with. One of: `left`, `right`, `middle` |
| X | `x` | Number | No |  | More options; tick Search only an area | Search region left; the whole image or screen when the region is empty. |
| Y | `y` | Number | No |  | More options; tick Search only an area | Search region top. |
| Width | `width` | Number | No |  | More options; tick Search only an area | Search region width. |
| Height | `height` | Number | No |  | More options; tick Search only an area | Search region height. |
| Tolerance | `tolerance` | Number | No | `10` | More options | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. Range: 0 to 100 |
| Occurrence | `occurrence` | Number | No | `1` | More options | Which match to use when the picture appears several times, counting in reading order from 1. Range: 1 to  |
| Position | `position` | Text | No | `center` | More options | Point reported (and clicked): center of the match, or its top_left corner; offsets are added to it. One of: `center`, `top_left` |
| Offset x | `offset_x` | Number | No | `0` | More options | Pixels added to the reported x, e.g. to reach a field to the right of a label picture. |
| Offset y | `offset_y` | Number | No | `0` | More options | Pixels added to the reported y. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Keep looking for up to this many seconds before failing; 0 looks once. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `x` | Number | Clicked x. |
| `y` | Number | Clicked y. |

*Time limit 60 s; needs: desktop.*

### Find image in image

`ooa.image/find_in_image` (action)

Look for a small picture inside a larger image file, optionally in a region and for its n-th appearance; returns the point of the match.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | File path | Yes |  | Main | Picture to look for. |
| Image | `image` | File path | Yes |  | Main | Image to search in. |
| X | `x` | Number | No |  | Main; tick Search only an area | Search region left; the whole image or screen when the region is empty. |
| Y | `y` | Number | No |  | Main; tick Search only an area | Search region top. |
| Width | `width` | Number | No |  | Main; tick Search only an area | Search region width. |
| Height | `height` | Number | No |  | Main; tick Search only an area | Search region height. |
| Tolerance | `tolerance` | Number | No | `10` | Main | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. Range: 0 to 100 |
| Occurrence | `occurrence` | Number | No | `1` | Main | Which match to use when the picture appears several times, counting in reading order from 1. Range: 1 to  |
| Position | `position` | Text | No | `center` | Main | Point reported (and clicked): center of the match, or its top_left corner; offsets are added to it. One of: `center`, `top_left` |
| Offset x | `offset_x` | Number | No | `0` | Main | Pixels added to the reported x, e.g. to reach a field to the right of a label picture. |
| Offset y | `offset_y` | Number | No | `0` | Main | Pixels added to the reported y. |
| Repeat times | `repeat_times` | Number | No | `1` | More options | How many times to look while the picture is not found; the image file is read again each time, e.g. while another program refreshes it. 1 looks once. Range: 1 to 1000 |
| Repeat delay (milliseconds) | `repeat_delay_ms` | Number | No | `500` | More options | Pause between two looks, in milliseconds. Range: 0 to 60000; Shown when: `{"repeat_times": ["!", "1"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when a match within the tolerance was found. |
| `x` | Number | Centre x of the match (or the chosen point). |
| `y` | Number | Centre y of the match (or the chosen point). |
| `similarity` | Number | Similarity of the best match in percent. |
| `count` | Number | Number of matches found (up to occurrence when it is above 1). |
| `left` | Number | Left edge of the match. |
| `top` | Number | Top edge of the match. |

*Time limit 60 s; safe to repeat.*

### Find image on screen

`ooa.image/find_on_screen` (action)

Look for a picture on the screen, optionally in a region and for its n-th appearance; returns the point of the match.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | File path | Yes |  | Main | Picture to look for. |
| X | `x` | Number | No |  | Main; tick Search only an area | Search region left; the whole image or screen when the region is empty. |
| Y | `y` | Number | No |  | Main; tick Search only an area | Search region top. |
| Width | `width` | Number | No |  | Main; tick Search only an area | Search region width. |
| Height | `height` | Number | No |  | Main; tick Search only an area | Search region height. |
| Tolerance | `tolerance` | Number | No | `10` | Main | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. Range: 0 to 100 |
| Occurrence | `occurrence` | Number | No | `1` | Main | Which match to use when the picture appears several times, counting in reading order from 1. Range: 1 to  |
| Position | `position` | Text | No | `center` | Main | Point reported (and clicked): center of the match, or its top_left corner; offsets are added to it. One of: `center`, `top_left` |
| Offset x | `offset_x` | Number | No | `0` | Main | Pixels added to the reported x, e.g. to reach a field to the right of a label picture. |
| Offset y | `offset_y` | Number | No | `0` | Main | Pixels added to the reported y. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | Main | Keep looking for up to this many seconds while the picture is not there; 0 looks once. found is false when time runs out. Range: 0 to 3600 |
| Interval (milliseconds) | `interval_ms` | Number | No | `500` | More options | Pause between two looks at the screen while waiting, in milliseconds. Range: 50 to 60000 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when found. |
| `x` | Number | Centre x on screen (or the chosen point). |
| `y` | Number | Centre y on screen (or the chosen point). |
| `similarity` | Number | Similarity in percent. |
| `count` | Number | Number of matches found (up to occurrence when it is above 1). |

*Time limit 60 s; needs: desktop.*

### Wait for image

`ooa.image/wait_for_image` (action)

Wait until a picture appears on the screen, or disappears from it, checking a region at a chosen interval (IMAGE.NOT_FOUND after the timeout).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | File path | Yes |  | Main | Picture to wait for. |
| Until gone | `until_gone` | Yes/No | No | No | Main | Wait for the picture to disappear instead, e.g. a loading spinner. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait. Range: 0 to  |
| X | `x` | Number | No |  | More options; tick Search only an area | Search region left; the whole image or screen when the region is empty. |
| Y | `y` | Number | No |  | More options; tick Search only an area | Search region top. |
| Width | `width` | Number | No |  | More options; tick Search only an area | Search region width. |
| Height | `height` | Number | No |  | More options; tick Search only an area | Search region height. |
| Tolerance | `tolerance` | Number | No | `10` | More options | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. Range: 0 to 100 |
| Interval (milliseconds) | `interval_ms` | Number | No | `500` | More options | Pause between two looks at the screen, in milliseconds. Range: 50 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `x` | Number | Centre x on screen. |
| `y` | Number | Centre y on screen. |

*Time limit 3600 s; needs: desktop.*

### Wait for screen change

`ooa.image/wait_for_screen_change` (action)

Wait until a region of the screen changes (e.g. a result appears), or until it stops changing (e.g. a page or application has finished drawing).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main; choice Watch = One window | Title of a window to watch: it is brought to the front and the region is measured from its top-left corner (the whole window when the region is empty). |
| X | `x` | Number | No |  | Main; tick Watch only an area | Region left; the whole screen when the region is empty. |
| Y | `y` | Number | No |  | Main; tick Watch only an area | Region top. |
| Width | `width` | Number | No |  | Main; tick Watch only an area | Region width. |
| Height | `height` | Number | No |  | Main; tick Watch only an area | Region height. |
| Initial wait (milliseconds) | `initial_wait_ms` | Number | No | `0` | Main | Wait this long before the first look, e.g. while a click starts the change. Range: 0 to  |
| Timeout (seconds) | `timeout_seconds` | Number | No | `30` | Main | Maximum wait. Range: 0 to  |
| Fail on timeout | `fail_on_timeout` | Yes/No | No | Yes | Main | Fail with IMAGE.NO_CHANGE (or IMAGE.NOT_STEADY) when time runs out; otherwise changed is false. |
| Mode | `mode` | Text | No | `change` | More options | change: wait until the region differs from how it looked at the start; settle: wait until it stays the same for stable_ms. One of: `change`, `settle` |
| Threshold percent | `threshold_percent` | Number | No | `1` | More options | Share of the region's pixels that must differ to count as a change (ignores a blinking cursor). Range: 0 to 100 |
| Unchanged for (milliseconds) | `stable_ms` | Number | No | `1000` | More options | settle: how long the region must stay unchanged. Range: 0 to ; Shown when: `{"mode": ["settle"]}` |
| Interval (milliseconds) | `interval_ms` | Number | No | `250` | More options | Pause between two looks at the screen. Range: 50 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changed` | Yes/No | True when the awaited change (or the steady state) happened in time. |
| `changed_percent` | Number | How much of the region differed from the first look, in percent. |

*Time limit 3600 s; needs: desktop.*

## Conditions

Used in If, Loop while and Wait until steps.

### Image is in image file

`ooa.image/image_in_image` (condition)

True when the picture appears inside an image file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Image | `image` | File path | Yes |  | Main | Image to search in. |
| Template | `template` | File path | Yes |  | Main | Picture to look for. |
| Tolerance | `tolerance` | Number | No | `10` | Main | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. |

### Image is on screen

`ooa.image/image_on_screen` (condition)

True when the picture is visible on the screen within the tolerance.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Template | `template` | File path | Yes |  | Main | Picture to look for. |
| Tolerance | `tolerance` | Number | No | `10` | Main | Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens. |
