# Insights

Toolkit `ooa.insights`, version 1.0.0. Record business values and timed transactions from agents for dashboards and ROI reports, and read them back.

[All toolkits](README.md)

## Contents

- **Insights**: Close transaction, Open transaction, Read business values, Tag business values

## Insights

### Close transaction

`ooa.insights/close_transaction` (action)

End a transaction opened with Open transaction and record its values, outcome and duration for dashboards.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Transaction | `transaction` | Text | Yes |  | Main | Name used in Open transaction. |
| Outcome | `outcome` | Text | No | `success` | Main | How the transaction ended. One of: `success`, `failure`, `business_exception` |
| Values | `values` | Dictionary | No |  | Main | Business values to record, e.g. {"amount": 120, "supplier": "Acme"}; leave out sensitive fields. Credentials are refused. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `duration_seconds` | Number | Seconds between Open and Close. |

*Time limit 60 s.*

### Open transaction

`ooa.insights/open_transaction` (action)

Mark the start of a business transaction (e.g. one invoice); Close transaction records its values and how long it took.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Transaction | `transaction` | Text | Yes |  | Main | Transaction name; Close transaction must use the same name. Example: `Invoice` |
| Category | `category` | Text | Yes |  | Main | Category (dashboard) the values go to. Example: `invoices` |

*Time limit 60 s.*

### Read business values

`ooa.insights/read_values` (action)

Read recorded values for a category as a table, optionally for a time range or one transaction name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Category | `category` | Text | Yes |  | Main | Category name. |
| Since | `since` | DateTime | No |  | Main | Only records on or after this date (UTC). |
| Until | `until` | DateTime | No |  | Main | Only records before this date (UTC). |
| Transaction | `transaction` | Text | No |  | More options | Only records of this transaction name (from Close transaction). |
| Maximum records | `max_records` | Number | No | `0` | More options | Return only the newest this many records; all when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per record. |
| `count` | Number | Number of records. |

*Time limit 60 s; safe to repeat.*

### Tag business values

`ooa.insights/tag_values` (action)

Record a set of values for a category, e.g. invoices: {amount, supplier, minutes_saved}.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Category | `category` | Text | Yes |  | Main | Category (dashboard) name. Example: `invoices` |
| Values | `values` | Dictionary | Yes |  | Main | Values to record; Credentials are refused. |

*Time limit 60 s.*
