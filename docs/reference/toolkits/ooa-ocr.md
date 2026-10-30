# OCR

Toolkit `ooa.ocr`, version 1.0.0. Read text from images, image URLs or the screen, and find or click text on the screen (Tesseract).

[All toolkits](README.md)

## Contents

- **OCR**: Click text on screen, Find text in image, Read text from image, Read text from screen, Read text from image URL
- **Conditions**: Text is on screen

## OCR

### Click text on screen

`ooa.ocr/click_text` (action)

Find a word or phrase (or its n-th appearance) on the screen with OCR and click it with the chosen button, optionally offset from its centre (OCR.TEXT_NOT_FOUND when absent).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Word or phrase to click. |
| Double-click | `double_click` | Yes/No | No | No | Main | Double-click. |
| Occurrence | `occurrence` | Number | No | `1` | Main | Which appearance to click, counting in reading order from 1. Range: 1 to  |
| Button | `button` | Text | No | `left` | More options | Mouse button to click with. One of: `left`, `right`, `middle` |
| Offset x | `offset_x` | Number | No | `0` | More options | Pixels to the right (negative: left) of the text's centre to click, e.g. to hit a field next to a label. |
| Offset y | `offset_y` | Number | No | `0` | More options | Pixels below (negative: above) the text's centre to click. |
| Language | `language` | Text | No | `eng` | More options | Tesseract language(s), e.g. eng, deu or eng+fra. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `x` | Number | Clicked x. |
| `y` | Number | Clicked y. |

*Time limit 300 s; needs: desktop.*

### Find text in image

`ooa.ocr/find_text_in_image` (action)

Locate a word or phrase in an image, or its n-th appearance; returns whether it was found, its centre and how often it appears.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Image file. |
| Text | `text` | Text | Yes |  | Main | Word or phrase to find. |
| Occurrence | `occurrence` | Number | No | `1` | Main | Which appearance to return, counting in reading order from 1. Range: 1 to  |
| Language | `language` | Text | No | `eng` | More options | Tesseract language(s), e.g. eng, deu or eng+fra. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when found. |
| `x` | Number | Centre x. |
| `y` | Number | Centre y. |
| `count` | Number | How many times the phrase appears in the image. |

*Time limit 300 s; safe to repeat; needs: filesystem.*

### Read text from image

`ooa.ocr/read_image` (action)

Extract text from an image file (PNG, JPG, TIFF, BMP), or from a region of it, optionally keeping only the text between two markers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Image file. |
| After text | `after_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text after the first occurrence of this marker, e.g. Invoice number:. |
| Before text | `before_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text before this marker (searched after the after_text marker). |
| Trim | `trim` | Yes/No | No | Yes | Main | Remove spaces and line breaks at the start and end of the result. |
| Language | `language` | Text | No | `eng` | Main | Tesseract language(s), e.g. eng, deu or eng+fra. |
| X | `x` | Number | No |  | More options; tick Read only an area | Region left in pixels; the whole image when the region is empty. |
| Y | `y` | Number | No |  | More options; tick Read only an area | Region top in pixels. |
| Width | `width` | Number | No |  | More options; tick Read only an area | Region width in pixels. |
| Height | `height` | Number | No |  | More options; tick Read only an area | Region height in pixels. |
| Page mode | `page_mode` | Number | No | `3` | More options | Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text. Range: 0 to 13 |
| Scale | `scale` | Number | No | `1` | More options | Enlarge the image by this factor (1 to 5) before reading; 2 or 3 helps with small text. Range: 1 to 5 |
| Image mode | `image_mode` | Text | No | `none` | More options | Prepare the image: none, gray, or black_white (a simple threshold that often helps with coloured backgrounds). One of: `none`, `gray`, `black_white` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Recognised text. |

*Time limit 300 s; safe to repeat; needs: filesystem.*

### Read text from screen

`ooa.ocr/read_screen` (action)

Capture the screen (or a region) and extract its text, optionally keeping only the text between two markers; for Citrix and applications without accessible controls.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Text | No |  | Main; choice Read from = One window | Read from this window (title text, a pattern with * or ?, or re:regex): it is brought to the front and x, y are measured from its top-left corner; the whole window when the area is empty. Example: `Invoice entry*` |
| X | `x` | Number | No |  | Main; tick Read only an area | Region left (whole screen when empty). |
| Y | `y` | Number | No |  | Main; tick Read only an area | Region top. |
| Width | `width` | Number | No |  | Main; tick Read only an area | Region width. |
| Height | `height` | Number | No |  | Main; tick Read only an area | Region height. |
| Wait (milliseconds) | `wait_ms` | Number | No | `0` | Main | Wait this many milliseconds before capturing, e.g. 1000 for a slow remote session. Range: 0 to  |
| After text | `after_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text after the first occurrence of this marker, e.g. Invoice number:. |
| Before text | `before_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text before this marker (searched after the after_text marker). |
| Trim | `trim` | Yes/No | No | Yes | Main | Remove spaces and line breaks at the start and end of the result. |
| Language | `language` | Text | No | `eng` | Main | Tesseract language(s), e.g. eng, deu or eng+fra. |
| Page mode | `page_mode` | Number | No | `6` | More options | Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text. Range: 0 to 13 |
| Scale | `scale` | Number | No | `2` | More options | Enlarge the image by this factor (1 to 5) before reading; 2 or 3 helps with small text. Range: 1 to 5 |
| Image mode | `image_mode` | Text | No | `none` | More options | Prepare the image: none, gray, or black_white (a simple threshold that often helps with coloured backgrounds). One of: `none`, `gray`, `black_white` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Recognised text. |

*Time limit 300 s; needs: desktop.*

### Read text from image URL

`ooa.ocr/read_url` (action)

Download an image from an http or https address and extract its text, optionally keeping only the text between two markers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Address of the image. Example: `https://example.com/scan.png` |
| After text | `after_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text after the first occurrence of this marker, e.g. Invoice number:. |
| Before text | `before_text` | Text | No |  | Main; tick Keep only part of the text | Keep only the text before this marker (searched after the after_text marker). |
| Trim | `trim` | Yes/No | No | Yes | Main | Remove spaces and line breaks at the start and end of the result. |
| Language | `language` | Text | No | `eng` | Main | Tesseract language(s), e.g. eng, deu or eng+fra. |
| Page mode | `page_mode` | Number | No | `3` | More options | Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text. Range: 0 to 13 |
| Scale | `scale` | Number | No | `1` | More options | Enlarge the image by this factor (1 to 5) before reading; 2 or 3 helps with small text. Range: 1 to 5 |
| Image mode | `image_mode` | Text | No | `none` | More options | Prepare the image: none, gray, or black_white (a simple threshold that often helps with coloured backgrounds). One of: `none`, `gray`, `black_white` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | Maximum time for the download. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Recognised text. |

*Time limit 300 s; safe to repeat; needs: network.*

## Conditions

Used in If, Loop while and Wait until steps.

### Text is on screen

`ooa.ocr/text_on_screen` (condition)

True when the word or phrase is visible on the screen (OCR).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Word or phrase. |
| Language | `language` | Text | No | `eng` | Main | Tesseract language(s), e.g. eng, deu or eng+fra. |
