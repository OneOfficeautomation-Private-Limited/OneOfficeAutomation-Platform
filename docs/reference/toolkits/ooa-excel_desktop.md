# Excel (desktop, needs Excel)

Toolkit `ooa.excel_desktop`, version 1.0.0. Run macros, refresh data and pivots, update links, export to PDF and convert workbooks using the installed Microsoft Excel.

[All toolkits](README.md)

## Contents

- **Convert**: Convert workbook, Export to PDF
- **Data**: Refresh all data, Update workbook links
- **Macros**: Run macro

## Convert

### Convert workbook

`ooa.excel_desktop/convert` (action)

Save a workbook in another format, e.g. .xls to .xlsx or .csv to .xlsx, optionally with a password on the new file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Source file. |
| Target | `target` | File path | Yes |  | Main | Target file; the format follows its extension (.xlsx, .xlsm, .xlsb, .xls, .csv, .txt, .ods, .xltx, .xltm). |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace the target when it already exists; false stops with EXCEL_DESKTOP.EXISTS. |
| Password | `password` | Credential (vault) | No |  | Main; tick The workbook has a password | Password needed to open the workbook; passed to Excel through the environment, not the command line. |
| New password | `new_password` | Credential (vault) | No |  | Main; tick Protect the new file with a password | Password needed to open the converted file; none when empty. |

*Time limit 600 s; needs: desktop.*

### Export to PDF

`ooa.excel_desktop/export_pdf` (action)

Export the whole workbook, or one sheet, to PDF, choosing quality, page range, print areas and document properties.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook. |
| Sheet | `sheet` | Text | No |  | Main; choice Sheets = One sheet | Only this sheet; the whole workbook when empty. |
| Target | `target` | File path | Yes |  | Main | PDF file to create. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace the PDF when it already exists; false stops with EXCEL_DESKTOP.EXISTS. |
| Quality | `quality` | Text | No | `standard` | More options | standard for printing; minimum for a smaller file to publish online. One of: `standard`, `minimum` |
| Include properties | `include_properties` | Yes/No | No | Yes | More options | Copy the workbook's title, author and other properties into the PDF. |
| Ignore print areas | `ignore_print_areas` | Yes/No | No | No | More options | Export whole sheets even where a print area is set. |
| From page | `from_page` | Number | No |  | More options | First printed page to export; from the first page when empty. |
| To page | `to_page` | Number | No |  | More options | Last printed page to export; to the last page when empty. |
| Password | `password` | Credential (vault) | No |  | More options | Password needed to open the workbook; passed to Excel through the environment, not the command line. |

*Time limit 600 s; needs: desktop.*

## Data

### Refresh all data

`ooa.excel_desktop/refresh_all` (action)

Refresh every query, connection and pivot table in the workbook, wait for completion, recalculate and save unless told not to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook. |
| Save | `save` | Yes/No | No | Yes | Main | Save the workbook after refreshing. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | Maximum time. |
| Password | `password` | Credential (vault) | No |  | More options | Password needed to open the workbook; passed to Excel through the environment, not the command line. |

*Time limit 3600 s; needs: desktop.*

### Update workbook links

`ooa.excel_desktop/update_links` (action)

Open the workbook in Excel, refresh the values taken from every linked workbook and save.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook. |
| Save | `save` | Yes/No | No | Yes | Main | Save the workbook after updating. |
| Password | `password` | Credential (vault) | No |  | More options | Password needed to open the workbook; passed to Excel through the environment, not the command line. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | More options | Maximum time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of linked workbooks updated. |

*Time limit 3600 s; needs: desktop.*

## Macros

### Run macro

`ooa.excel_desktop/run_macro` (action)

Open a macro-enabled workbook, optionally password-protected, run a VBA macro with up to 10 arguments and optionally save.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook (.xlsm/.xlsb/.xls). |
| Macro | `macro` | Text | Yes |  | Main | Macro name. Example: `Module1.BuildReport` |
| Arguments | `arguments` | List | No |  | Main | Arguments passed to the macro (text). |
| Save | `save` | Yes/No | No | Yes | Main | Save the workbook afterwards. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | More options | Maximum run time. |
| Password | `password` | Credential (vault) | No |  | More options | Password needed to open the workbook; passed to Excel through the environment, not the command line. |
| Visible | `visible` | Yes/No | No | No | More options | Show the Excel window while the agent works, e.g. to watch a macro; hidden by default. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Value returned by the macro (for Functions), as text. |

*Time limit 3600 s; needs: desktop.*
