# Printer

Toolkit `ooa.printer`, version 1.0.0. List printers and print files.

[All toolkits](README.md)

## Contents

- **Printer**: Get default printer, Get printers, Print file, Remove printer, Set default printer

## Printer

### Get default printer

`ooa.printer/get_default` (action)

Return the name of the runner's default printer.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `printer` | Text | Default printer name, or empty when none is set. |

*Time limit 60 s; safe to repeat.*

### Get printers

`ooa.printer/list_printers` (action)

List the printers installed on the runner.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `printers` | List | Printer names. |
| `default_printer` | Text | Default printer name, or empty. |

*Time limit 60 s; safe to repeat.*

### Print file

`ooa.printer/print_file` (action)

Print a file on a named printer, or on the default printer using the file's default application.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to print. |
| Printer | `printer` | Text | No |  | Main | Printer name; default printer when empty. |
| Copies | `copies` | Number | No | `1` | Main | Number of copies. Range: 1 to 999 |

*Time limit 60 s; needs: filesystem.*

### Remove printer

`ooa.printer/remove_printer` (action)

Uninstall a printer from the runner (Windows); needs confirm because it cannot be undone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Printer | `printer` | Text | Yes |  | Main | Exact printer name from Get printers. |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true to remove the printer. |

*Time limit 120 s.*

### Set default printer

`ooa.printer/set_default` (action)

Make a printer the default for the runner's signed-in user (Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Printer | `printer` | Text | Yes |  | Main | Exact printer name from Get printers. |

*Time limit 120 s.*
