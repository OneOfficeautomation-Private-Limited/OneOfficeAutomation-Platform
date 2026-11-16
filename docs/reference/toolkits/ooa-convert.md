# Convert

Toolkit `ooa.convert`, version 1.0.0. Change a value from one type to another: text, number, Yes/No, date, list, dictionary or JSON.

[All toolkits](README.md)

## Contents

- **Convert**: Convert value

## Convert

### Convert value

`ooa.convert/convert` (action)

Turn a value into another type: text into a number or date, a list into text, JSON text into a dictionary and so on, with a fallback when it cannot be converted.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | Any | No |  | Main | The value to convert: a variable or something typed in. |
| Convert to | `to` | Text | No | `text` | Main | The type it becomes. Yes/No is a Boolean; JSON text writes a list or dictionary as JSON. One of: `text`, `number`, `boolean`, `date_time`, `list`, `dictionary`, `json_text` |
| Date pattern | `date_pattern` | Text | No |  | Main | Pattern to read a date from text, or to write a date as text, e.g. dd/MM/yyyy or yyyy-MM-dd HH:mm; empty reads ISO and common forms. Example: `dd/MM/yyyy`; Shown when: `{"to": ["date_time", "text"]}` |
| Locale | `locale` | Text | No |  | Main | Read or write numbers the locale's way, e.g. de-DE reads 1.234,50; empty: 1,234.50 or 1234.50. Example: `de-DE`; Shown when: `{"to": ["number", "text"]}` |
| Separator | `separator` | Text | No | `,` | Main | Text to list: split at this (spaces around items are trimmed). List to text: put between items. A line break is \n. Shown when: `{"to": ["list", "text"]}` |
| On error | `on_error` | Text | No | `fail` | More options | When the value cannot be converted: fail with CONVERT.FAILED, or give the fallback value. One of: `fail`, `fallback` |
| Fallback | `fallback` | Any | No |  | More options | The value to give when the conversion fails. Shown when: `{"on_error": ["fallback"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The converted value. |
| `ok` | Yes/No | False when the fallback was used. |

*Time limit 60 s; safe to repeat.*
