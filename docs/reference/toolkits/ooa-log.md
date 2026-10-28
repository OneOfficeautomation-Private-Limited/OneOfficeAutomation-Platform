# Log to file

Toolkit `ooa.log`, version 1.0.0. Write timestamped lines to a log file or to the run log in Command Center; secrets are masked automatically.

[All toolkits](README.md)

## Contents

- **Logging**: Log to Command Center, Log variables to file, Log to file

## Logging

### Log to Command Center

`ooa.log/log_to_command_center` (action)

Add a message, with optional details, to this run's log so it appears in Command Center with the run's activity.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | Yes |  | Main | Text to log; ${...} interpolation allowed. |
| Details | `details` | Dictionary | No |  | Main | Extra name and value pairs added to the entry as JSON. |
| Level | `level` | Text | No | `INFO` | Main | Level of the log entry. One of: `DEBUG`, `INFO`, `WARN`, `ERROR` |
| Source | `source` | Text | No |  | More options | Short tag for where the entry comes from, shown in brackets before the message, e.g. [invoice-check]. Example: `invoice-check` |

*Time limit 60 s.*

### Log variables to file

`ooa.log/log_variables` (action)

Write one name=value line per variable to a log file, to record the state of a run; credentials are written as ****.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Log file path, e.g. logs/state.log. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace the file instead of appending. |
| Timestamp | `timestamp` | Yes/No | No | Yes | Main | Start the heading with the date and time. |
| Variables | `variables` | Dictionary | Yes |  | Main | Names and values to write, e.g. {"invoice": "$invoice", "total": "$total"}. |
| Title | `title` | Text | No |  | More options | Heading line written before the variables. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | File encoding: utf-8, utf-16le (Unicode, written with a byte-order mark) or ansi (the runner's code page). Match the existing file when appending. One of: `utf-8`, `utf-16le`, `ansi` |
| Timestamp format | `timestamp_format` | Text | No |  | More options | Pattern for the date and time; ISO 8601 with time zone when empty. Example: `yyyy-MM-dd HH:mm:ss`; Shown when: `{"timestamp": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `lines` | Number | Number of variable lines written. |

*Time limit 60 s; needs: filesystem.*

### Log to file

`ooa.log/write` (action)

Append (or overwrite with) a line in a text log file, with optional timestamp and level.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Log file path, e.g. logs/invoices.log. |
| Message | `message` | Text | Yes |  | Main | Text to log; ${...} interpolation allowed. |
| Timestamp | `timestamp` | Yes/No | No | Yes | Main | Start the line with the date and time. |
| Timestamp format | `timestamp_format` | Text | No |  | Main | Pattern for the date and time; ISO 8601 with time zone when empty. Example: `yyyy-MM-dd HH:mm:ss`; Shown when: `{"timestamp": ["true"]}` |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace the file instead of appending. |
| Encoding | `encoding` | Text | No | `utf-8` | Main | File encoding: utf-8, utf-16le (Unicode, written with a byte-order mark) or ansi (the runner's code page). Match the existing file when appending. One of: `utf-8`, `utf-16le`, `ansi` |
| Level | `level` | Text | No | `INFO` | More options | Level label. One of: `DEBUG`, `INFO`, `WARN`, `ERROR` |

*Time limit 60 s; needs: filesystem.*
